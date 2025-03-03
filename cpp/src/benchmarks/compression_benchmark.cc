/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <arrow/filesystem/filesystem.h>
#include <arrow/io/interfaces.h>
#include <arrow/memory_pool.h>
#include <arrow/record_batch.h>
#include <arrow/type.h>
#include <benchmark/benchmark.h>
#include <execinfo.h>
#include <parquet/arrow/reader.h>
#include <parquet/file_reader.h>
#include <sched.h>
#include <sys/mman.h>

#include <chrono>
#include <utility>

#include "operators/shuffle/splitter.h"
#include "utils/macros.h"

void print_trace(void) {
  char** strings;
  size_t i, size;
  enum Constexpr { MAX_SIZE = 1024 };
  void* array[MAX_SIZE];
  size = backtrace(array, MAX_SIZE);
  strings = backtrace_symbols(array, size);
  for (i = 0; i < size; i++)
    printf("    %s\n", strings[i]);
  puts("");
  free(strings);
}

using arrow::RecordBatchReader;
using arrow::Status;
using gluten::GlutenException;
using gluten::shuffle::SplitOptions;
using gluten::shuffle::Splitter;

namespace sparkcolumnarplugin {
namespace shuffle {

#define ALIGNMENT 2 * 1024 * 1024

class MyMemoryPool : public arrow::MemoryPool {
 public:
  explicit MyMemoryPool() {}

  Status Allocate(int64_t size, uint8_t** out) override {
    RETURN_NOT_OK(pool_->Allocate(size, out));
    stats_.UpdateAllocatedBytes(size);
    // std::cout << "Allocate: size = " << size << " addr = " << std::hex <<
    // (uint64_t)*out << std::dec << std::endl; print_trace();
    return arrow::Status::OK();
  }

  Status Reallocate(int64_t old_size, int64_t new_size, uint8_t** ptr)
      override {
    auto old_ptr = *ptr;
    RETURN_NOT_OK(pool_->Reallocate(old_size, new_size, ptr));
    stats_.UpdateAllocatedBytes(new_size - old_size);
    // std::cout << "Reallocate: old_size = " << old_size << " old_ptr = " <<
    // std::hex << (uint64_t)old_ptr << std::dec << " new_size = " << new_size
    // << " addr = " << std::hex << (uint64_t)*ptr << std::dec << std::endl;
    // print_trace();
    return arrow::Status::OK();
  }

  void Free(uint8_t* buffer, int64_t size) override {
    pool_->Free(buffer, size);
    stats_.UpdateAllocatedBytes(-size);
    // std::cout << "Free: size = " << size << " addr = " << std::hex <<
    // (uint64_t)buffer
    // << std::dec << std::endl; print_trace();
  }

  int64_t bytes_allocated() const override {
    return stats_.bytes_allocated();
  }

  int64_t max_memory() const override {
    return pool_->max_memory();
  }

  std::string backend_name() const override {
    return pool_->backend_name();
  }

 private:
  arrow::MemoryPool* pool_ = arrow::default_memory_pool();
  arrow::internal::MemoryPoolStats stats_;
};

// #define ENABLELARGEPAGE

class LargePageMemoryPool : public arrow::MemoryPool {
 public:
  explicit LargePageMemoryPool() {}

  ~LargePageMemoryPool() override = default;

  Status Allocate(int64_t size, uint8_t** out) override {
#ifdef ENABLELARGEPAGE
    if (size < 2 * 1024 * 1024) {
      return pool_->Allocate(size, out);
    } else {
      Status st = pool_->AlignAllocate(size, out, ALIGNMENT);
      madvise(*out, size, /*MADV_HUGEPAGE */ 14);
      //std::cout << "Allocate: size = " << size << " addr = "  \
     //    << std::hex << (uint64_t)*out  << " end = " << std::hex << (uint64_t)(*out+size) << std::dec << std::endl;
      return st;
    }
#else
    return pool_->Allocate(size, out);
#endif
  }

  Status Reallocate(int64_t old_size, int64_t new_size, uint8_t** ptr)
      override {
    return pool_->Reallocate(old_size, new_size, ptr);
#ifdef ENABLELARGEPAGE
    if (new_size < 2 * 1024 * 1024) {
      return pool_->Reallocate(old_size, new_size, ptr);
    } else {
      Status st = pool_->AlignReallocate(old_size, new_size, ptr, ALIGNMENT);
      madvise(*ptr, new_size, /*MADV_HUGEPAGE */ 14);
      return st;
    }
#else
    return pool_->Reallocate(old_size, new_size, ptr);
#endif
  }

  void Free(uint8_t* buffer, int64_t size) override {
#ifdef ENABLELARGEPAGE
    if (size < 2 * 1024 * 1024) {
      pool_->Free(buffer, size);
    } else {
      pool_->Free(buffer, size, ALIGNMENT);
    }
#else
    pool_->Free(buffer, size);
#endif
  }

  int64_t bytes_allocated() const override {
    return pool_->bytes_allocated();
  }

  int64_t max_memory() const override {
    return pool_->max_memory();
  }

  std::string backend_name() const override {
    return "LargePageMemoryPool";
  }

 private:
  MemoryPool* pool_ = arrow::default_memory_pool();
};

class BenchmarkCompression {
 public:
  explicit BenchmarkCompression(
      const std::string& file_name,
      uint32_t split_buffer_size) {
    GetRecordBatchReader(file_name, split_buffer_size);
  }

  void GetRecordBatchReader(
      const std::string& input_file,
      uint32_t split_buffer_size) {
    std::unique_ptr<::parquet::arrow::FileReader> parquet_reader;
    std::shared_ptr<RecordBatchReader> record_batch_reader;

    std::shared_ptr<arrow::fs::FileSystem> fs;
    std::string file_name;
    GLUTEN_ASSIGN_OR_THROW(
        fs, arrow::fs::FileSystemFromUriOrPath(input_file, &file_name))

    GLUTEN_ASSIGN_OR_THROW(file, fs->OpenInputFile(file_name));

    properties.set_batch_size(split_buffer_size);
    properties.set_pre_buffer(false);
    properties.set_use_threads(false);

    GLUTEN_THROW_NOT_OK(::parquet::arrow::FileReader::Make(
        arrow::default_memory_pool(),
        ::parquet::ParquetFileReader::Open(file),
        properties,
        &parquet_reader));

    GLUTEN_THROW_NOT_OK(parquet_reader->GetSchema(&schema));

    auto num_rowgroups = parquet_reader->num_row_groups();

    for (int i = 0; i < num_rowgroups; ++i) {
      row_group_indices.push_back(i);
    }

    auto num_columns = schema->num_fields();
    for (int i = 0; i < num_columns; ++i) {
      column_indices.push_back(i);
    }
  }

  void operator()(benchmark::State& state) {
    // SetCPU(state.thread_index());
    ipc_write_options = arrow::ipc::IpcWriteOptions::Defaults();
    ipc_write_options.use_threads = false;
    auto compression_type = (arrow::Compression::type)state.range(0);
    auto split_buffer_size = (uint32_t)state.range(1);
    GLUTEN_ASSIGN_OR_THROW(
        ipc_write_options.codec, arrow::util::Codec::Create(compression_type));
    std::shared_ptr<arrow::MemoryPool> pool =
        std::make_shared<LargePageMemoryPool>();
    ipc_write_options.memory_pool = pool.get();

    int64_t elapse_read = 0;
    int64_t num_batches = 0;
    int64_t num_rows = 0;
    int64_t compress_time = 0;

    auto start_time = std::chrono::steady_clock::now();

    DoCompress(elapse_read, num_batches, num_rows, compress_time, state);
    auto end_time = std::chrono::steady_clock::now();
    auto total_time = (end_time - start_time).count();

    state.counters["rowgroups"] = benchmark::Counter(
        row_group_indices.size(),
        benchmark::Counter::kAvgThreads,
        benchmark::Counter::OneK::kIs1000);
    state.counters["columns"] = benchmark::Counter(
        column_indices.size(),
        benchmark::Counter::kAvgThreads,
        benchmark::Counter::OneK::kIs1000);
    state.counters["batches"] = benchmark::Counter(
        num_batches,
        benchmark::Counter::kAvgThreads,
        benchmark::Counter::OneK::kIs1000);
    state.counters["num_rows"] = benchmark::Counter(
        num_rows,
        benchmark::Counter::kAvgThreads,
        benchmark::Counter::OneK::kIs1000);
    state.counters["batch_buffer_size"] = benchmark::Counter(
        split_buffer_size,
        benchmark::Counter::kAvgThreads,
        benchmark::Counter::OneK::kIs1024);

    state.counters["parquet_parse"] = benchmark::Counter(
        elapse_read,
        benchmark::Counter::kAvgThreads,
        benchmark::Counter::OneK::kIs1000);

    state.counters["compress_time"] = benchmark::Counter(
        compress_time,
        benchmark::Counter::kAvgThreads,
        benchmark::Counter::OneK::kIs1000);

    state.counters["total_time"] = benchmark::Counter(
        total_time,
        benchmark::Counter::kAvgThreads,
        benchmark::Counter::OneK::kIs1000);
  }

 protected:
  long SetCPU(uint32_t cpuindex) {
    cpu_set_t cs;
    CPU_ZERO(&cs);
    CPU_SET(cpuindex, &cs);
    return sched_setaffinity(0, sizeof(cs), &cs);
  }

  virtual void DoCompress(
      int64_t& elapse_read,
      int64_t& num_batches,
      int64_t& num_rows,
      int64_t& compress_time,
      benchmark::State& state) {}

 protected:
  std::shared_ptr<arrow::io::RandomAccessFile> file;
  std::vector<int> row_group_indices;
  std::vector<int> column_indices;
  std::shared_ptr<arrow::Schema> schema;
  parquet::ArrowReaderProperties properties;
  arrow::ipc::IpcWriteOptions ipc_write_options;
};

class BenchmarkCompression_IterateScan_Benchmark : public BenchmarkCompression {
 public:
  explicit BenchmarkCompression_IterateScan_Benchmark(
      const std::string& filename,
      uint32_t split_buffer_size)
      : BenchmarkCompression(filename, split_buffer_size) {}

 protected:
  void DoCompress(
      int64_t& elapse_read,
      int64_t& num_batches,
      int64_t& num_rows,
      int64_t& compress_time,
      benchmark::State& state) override {
    std::shared_ptr<arrow::RecordBatch> record_batch;

    std::unique_ptr<::parquet::arrow::FileReader> parquet_reader;
    std::shared_ptr<RecordBatchReader> record_batch_reader;
    GLUTEN_THROW_NOT_OK(::parquet::arrow::FileReader::Make(
        arrow::default_memory_pool(),
        ::parquet::ParquetFileReader::Open(file),
        properties,
        &parquet_reader));

    for (auto _ : state) {
      GLUTEN_THROW_NOT_OK(parquet_reader->GetRecordBatchReader(
          row_group_indices, column_indices, &record_batch_reader));
      TIME_NANO_OR_THROW(
          elapse_read, record_batch_reader->ReadNext(&record_batch));
      while (record_batch) {
        num_batches += 1;
        num_rows += record_batch->num_rows();
        for (auto i = 0; i < record_batch->num_columns(); ++i) {
          record_batch->column(i)->data()->buffers[0] = nullptr;
        }
        auto payload = std::make_shared<arrow::ipc::IpcPayload>();

        TIME_NANO_OR_THROW(
            compress_time,
            arrow::ipc::GetRecordBatchPayload(
                *record_batch, ipc_write_options, payload.get()));
        std::cout << "Compressed " << num_batches << " batches" << std::endl;
        TIME_NANO_OR_THROW(
            elapse_read, record_batch_reader->ReadNext(&record_batch));
      }
    }
  }
};

} // namespace shuffle
} // namespace sparkcolumnarplugin

int main(int argc, char** argv) {
  uint32_t iterations = 1;
  uint32_t threads = 1;
  std::string datafile;
  auto codec = arrow::Compression::LZ4_FRAME;
  uint32_t split_buffer_size = 8192;

  for (int i = 0; i < argc; i++) {
    if (strcmp(argv[i], "--iterations") == 0) {
      iterations = atol(argv[i + 1]);
    } else if (strcmp(argv[i], "--threads") == 0) {
      threads = atol(argv[i + 1]);
    } else if (strcmp(argv[i], "--file") == 0) {
      datafile = argv[i + 1];
    } else if (strcmp(argv[i], "--qat") == 0) {
      std::cout << "QAT is used as codec" << std::endl;
      codec = arrow::Compression::GZIP;
    } else if (strcmp(argv[i], "--buffer-size") == 0) {
      split_buffer_size = atol(argv[i + 1]);
    }
  }
  std::cout << "iterations = " << iterations << std::endl;
  std::cout << "threads = " << threads << std::endl;
  std::cout << "datafile = " << datafile << std::endl;

  sparkcolumnarplugin::shuffle::BenchmarkCompression_IterateScan_Benchmark bck(
      datafile, split_buffer_size);

  benchmark::RegisterBenchmark("BenchmarkCompression::IterateScan", bck)
      ->Iterations(iterations)
      ->Args({
          codec,
          split_buffer_size,
      })
      ->Threads(threads)
      ->ReportAggregatesOnly(false)
      ->MeasureProcessCPUTime()
      ->Unit(benchmark::kSecond);

  benchmark::Initialize(&argc, argv);
  benchmark::RunSpecifiedBenchmarks();
  benchmark::Shutdown();
}
