/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.raptor.storage;

import com.facebook.presto.orc.BooleanVector;
import com.facebook.presto.orc.DoubleVector;
import com.facebook.presto.orc.FileOrcDataSource;
import com.facebook.presto.orc.LongVector;
import com.facebook.presto.orc.OrcRecordReader;
import com.facebook.presto.orc.SliceVector;
import com.facebook.presto.spi.classloader.ThreadContextClassLoader;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.File;
import java.util.List;

import static com.facebook.presto.raptor.storage.OrcTestingUtil.createReader;
import static com.facebook.presto.raptor.storage.OrcTestingUtil.createReaderNoRows;
import static com.facebook.presto.raptor.storage.OrcTestingUtil.octets;
import static com.facebook.presto.raptor.storage.StorageType.BOOLEAN;
import static com.facebook.presto.raptor.storage.StorageType.BYTES;
import static com.facebook.presto.raptor.storage.StorageType.DOUBLE;
import static com.facebook.presto.raptor.storage.StorageType.LONG;
import static com.facebook.presto.raptor.storage.StorageType.STRING;
import static com.google.common.io.Files.createTempDir;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static io.airlift.testing.FileUtils.deleteRecursively;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;

public class TestOrcRowSink
{
    private File directory;

    @BeforeClass
    public void setup()
    {
        directory = createTempDir();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown()
            throws Exception
    {
        deleteRecursively(directory);
    }

    @Test
    public void testWriter()
            throws Exception
    {
        List<Long> columnIds = ImmutableList.of(1L, 2L, 4L, 6L, 7L);
        List<StorageType> columnTypes = ImmutableList.of(LONG, STRING, BYTES, DOUBLE, BOOLEAN);
        Optional<Long> sampleWeightColumnId = Optional.absent();

        File file = new File(directory, System.nanoTime() + ".orc");

        byte[] bytes1 = octets(0x00, 0xFE, 0xFF);
        byte[] bytes3 = octets(0x01, 0x02, 0x19, 0x80);

        try (ThreadContextClassLoader ignored = new ThreadContextClassLoader(new EmptyClassLoader());
                RowSink sink = new OrcRowSink(columnIds, columnTypes, sampleWeightColumnId, file)) {
            sink.beginRecord(1);
            sink.appendLong(123);
            sink.appendString("hello");
            sink.appendBytes(bytes1);
            sink.appendDouble(123.456);
            sink.appendBoolean(true);
            sink.finishRecord();

            sink.beginRecord(1);
            sink.appendNull();
            sink.appendString("world");
            sink.appendNull();
            sink.appendDouble(Double.POSITIVE_INFINITY);
            sink.appendNull();
            sink.finishRecord();

            sink.beginRecord(1);
            sink.appendLong(456);
            sink.appendString("bye");
            sink.appendBytes(bytes3);
            sink.appendDouble(Double.NaN);
            sink.appendBoolean(false);
            sink.finishRecord();
        }

        try (FileOrcDataSource dataSource = new FileOrcDataSource(file)) {
            OrcRecordReader reader = createReader(dataSource, columnIds);
            assertEquals(reader.getTotalRowCount(), 3);
            assertEquals(reader.getPosition(), 0);

            assertEquals(reader.nextBatch(), 3);
            assertEquals(reader.getPosition(), 3);

            LongVector longVector = new LongVector();
            reader.readVector(0, longVector);
            assertEquals(longVector.isNull[0], false);
            assertEquals(longVector.isNull[1], true);
            assertEquals(longVector.isNull[2], false);
            assertEquals(longVector.vector[0], 123L);
            assertEquals(longVector.vector[2], 456L);

            SliceVector stringVector = new SliceVector();
            reader.readVector(1, stringVector);
            assertEquals(stringVector.vector[0], utf8Slice("hello"));
            assertEquals(stringVector.vector[1], utf8Slice("world"));
            assertEquals(stringVector.vector[2], utf8Slice("bye"));

            SliceVector sliceVector = new SliceVector();
            reader.readVector(2, sliceVector);
            assertEquals(sliceVector.vector[0], wrappedBuffer(bytes1));
            assertEquals(sliceVector.vector[1], null);
            assertEquals(sliceVector.vector[2], wrappedBuffer(bytes3));

            DoubleVector doubleVector = new DoubleVector();
            reader.readVector(3, doubleVector);
            assertEquals(doubleVector.isNull[0], false);
            assertEquals(doubleVector.isNull[1], false);
            assertEquals(doubleVector.isNull[2], false);
            assertEquals(doubleVector.vector[0], 123.456);
            assertEquals(doubleVector.vector[1], Double.POSITIVE_INFINITY);
            assertEquals(doubleVector.vector[2], Double.NaN);

            BooleanVector booleanVector = new BooleanVector();
            reader.readVector(4, booleanVector);
            assertEquals(booleanVector.isNull[0], false);
            assertEquals(booleanVector.isNull[1], true);
            assertEquals(booleanVector.isNull[2], false);
            assertEquals(booleanVector.vector[0], true);
            assertEquals(booleanVector.vector[2], false);

            assertEquals(reader.nextBatch(), -1);
        }

        File crcFile = new File(file.getParentFile(), "." + file.getName() + ".crc");
        assertFalse(crcFile.exists());
    }

    @SuppressWarnings("EmptyTryBlock")
    @Test
    public void testWriterZeroRows()
            throws Exception
    {
        List<Long> columnIds = ImmutableList.of(1L);
        List<StorageType> columnTypes = ImmutableList.of(LONG);
        Optional<Long> sampleWeightColumnId = Optional.absent();

        File file = new File(directory, System.nanoTime() + ".orc");

        try (RowSink ignored = new OrcRowSink(columnIds, columnTypes, sampleWeightColumnId, file)) {
            // no rows
        }

        try (FileOrcDataSource dataSource = new FileOrcDataSource(file)) {
            OrcRecordReader reader = createReaderNoRows(dataSource);
            assertEquals(reader.getTotalRowCount(), 0);
            assertEquals(reader.getPosition(), 0);

            assertEquals(reader.nextBatch(), -1);
        }
    }

    @SuppressWarnings("EmptyClass")
    private static class EmptyClassLoader
            extends ClassLoader
    {
        protected EmptyClassLoader()
        {
            super(null);
        }
    }
}
