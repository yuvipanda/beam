/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.io;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.Serializable;
import org.apache.beam.sdk.annotations.Experimental;
import org.apache.beam.sdk.annotations.Experimental.Kind;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.io.FileIO.ReadableFile;
import org.apache.beam.sdk.io.fs.MatchResult.Metadata;
import org.apache.beam.sdk.io.fs.ResourceId;
import org.apache.beam.sdk.io.range.OffsetRange;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.Reshuffle;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;

/**
 * Reads each file in the input {@link PCollection} of {@link ReadableFile} using given parameters
 * for splitting files into offset ranges and for creating a {@link FileBasedSource} for a file. The
 * input {@link PCollection} must not contain {@link ResourceId#isDirectory directories}.
 *
 * <p>To obtain the collection of {@link ReadableFile} from a filepattern, use {@link
 * FileIO#readMatches()}.
 */
@Experimental(Kind.SOURCE_SINK)
public class ReadAllViaFileBasedSource<T>
    extends PTransform<PCollection<ReadableFile>, PCollection<T>> {

  public static final boolean DEFAULT_USES_RESHUFFLE = true;
  private final long desiredBundleSizeBytes;
  private final SerializableFunction<String, ? extends FileBasedSource<T>> createSource;
  private final Coder<T> coder;
  private final ReadFileRangesFnExceptionHandler exceptionHandler;
  private final boolean usesReshuffle;

  public ReadAllViaFileBasedSource(
      long desiredBundleSizeBytes,
      SerializableFunction<String, ? extends FileBasedSource<T>> createSource,
      Coder<T> coder) {
    this(
        desiredBundleSizeBytes,
        createSource,
        coder,
        DEFAULT_USES_RESHUFFLE,
        new ReadFileRangesFnExceptionHandler());
  }

  public ReadAllViaFileBasedSource(
      long desiredBundleSizeBytes,
      SerializableFunction<String, ? extends FileBasedSource<T>> createSource,
      Coder<T> coder,
      boolean usesReshuffle,
      ReadFileRangesFnExceptionHandler exceptionHandler) {
    this.desiredBundleSizeBytes = desiredBundleSizeBytes;
    this.createSource = createSource;
    this.coder = coder;
    this.usesReshuffle = usesReshuffle;
    this.exceptionHandler = exceptionHandler;
  }

  @Override
  public PCollection<T> expand(PCollection<ReadableFile> input) {
    PCollection<KV<ReadableFile, OffsetRange>> ranges =
        input.apply("Split into ranges", ParDo.of(new SplitIntoRangesFn(desiredBundleSizeBytes)));
    if (usesReshuffle) {
      ranges = ranges.apply("Reshuffle", Reshuffle.viaRandomKey());
    }
    return ranges
        .apply("Read ranges", ParDo.of(new ReadFileRangesFn<T>(createSource, exceptionHandler)))
        .setCoder(coder);
  }

  private static class SplitIntoRangesFn extends DoFn<ReadableFile, KV<ReadableFile, OffsetRange>> {
    private final long desiredBundleSizeBytes;

    private SplitIntoRangesFn(long desiredBundleSizeBytes) {
      this.desiredBundleSizeBytes = desiredBundleSizeBytes;
    }

    @ProcessElement
    public void process(ProcessContext c) {
      Metadata metadata = c.element().getMetadata();
      if (!metadata.isReadSeekEfficient()) {
        c.output(KV.of(c.element(), new OffsetRange(0, metadata.sizeBytes())));
        return;
      }
      for (OffsetRange range :
          new OffsetRange(0, metadata.sizeBytes()).split(desiredBundleSizeBytes, 0)) {
        c.output(KV.of(c.element(), range));
      }
    }
  }

  private static class ReadFileRangesFn<T> extends DoFn<KV<ReadableFile, OffsetRange>, T> {
    private final SerializableFunction<String, ? extends FileBasedSource<T>> createSource;
    private final ReadFileRangesFnExceptionHandler exceptionHandler;

    private ReadFileRangesFn(
        SerializableFunction<String, ? extends FileBasedSource<T>> createSource,
        ReadFileRangesFnExceptionHandler exceptionHandler) {
      this.createSource = createSource;
      this.exceptionHandler = exceptionHandler;
    }

    @ProcessElement
    @SuppressFBWarnings(
        value = "RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE",
        justification = "https://github.com/spotbugs/spotbugs/issues/756")
    public void process(ProcessContext c) throws IOException {
      ReadableFile file = c.element().getKey();
      OffsetRange range = c.element().getValue();
      FileBasedSource<T> source =
          CompressedSource.from(createSource.apply(file.getMetadata().resourceId().toString()))
              .withCompression(file.getCompression());
      try (BoundedSource.BoundedReader<T> reader =
          source
              .createForSubrangeOfFile(file.getMetadata(), range.getFrom(), range.getTo())
              .createReader(c.getPipelineOptions())) {
        for (boolean more = reader.start(); more; more = reader.advance()) {
          c.output(reader.getCurrent());
        }
      } catch (RuntimeException e) {
        if (exceptionHandler.apply(file, range, e)) {
          throw e;
        }
      }
    }
  }

  /** A class to handle errors which occur during file reads. */
  public static class ReadFileRangesFnExceptionHandler implements Serializable {

    /*
     * Applies the desired handler logic to the given exception and returns
     * if the exception should be thrown.
     */
    public boolean apply(ReadableFile file, OffsetRange range, Exception e) {
      return true;
    }
  }
}
