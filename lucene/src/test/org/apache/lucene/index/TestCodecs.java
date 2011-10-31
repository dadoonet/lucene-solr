package org.apache.lucene.index;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;

import org.apache.lucene.analysis.MockAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.FieldInfo.IndexOptions;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.codecs.Codec;
import org.apache.lucene.index.codecs.FieldsConsumer;
import org.apache.lucene.index.codecs.FieldsProducer;
import org.apache.lucene.index.codecs.PostingsConsumer;
import org.apache.lucene.index.codecs.TermStats;
import org.apache.lucene.index.codecs.TermsConsumer;
import org.apache.lucene.index.codecs.lucene3x.Lucene3xCodec;
import org.apache.lucene.index.codecs.lucene3x.Lucene3xPostingsFormat;
import org.apache.lucene.index.codecs.mocksep.MockSepPostingsFormat;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.LuceneTestCase;
import org.apache.lucene.util.OpenBitSet;
import org.apache.lucene.util.Version;
import org.apache.lucene.util._TestUtil;
import org.junit.BeforeClass;

// TODO: test multiple codecs here?

// TODO
//   - test across fields
//   - fix this test to run once for all codecs
//   - make more docs per term, to test > 1 level skipping
//   - test all combinations of payloads/not and omitTF/not
//   - test w/ different indexDivisor
//   - test field where payload length rarely changes
//   - 0-term fields
//   - seek/skip to same term/doc i'm already on
//   - mix in deleted docs
//   - seek, skip beyond end -- assert returns false
//   - seek, skip to things that don't exist -- ensure it
//     goes to 1 before next one known to exist
//   - skipTo(term)
//   - skipTo(doc)

//nocommit: add any custom codecs here to test-framework so they can be 'loaded'
//automagically
public class TestCodecs extends LuceneTestCase {
  private static String[] fieldNames = new String[] {"one", "two", "three", "four"};

  private static int NUM_TEST_ITER;
  private final static int NUM_TEST_THREADS = 3;
  private final static int NUM_FIELDS = 4;
  private final static int NUM_TERMS_RAND = 50; // must be > 16 to test skipping
  private final static int DOC_FREQ_RAND = 500; // must be > 16 to test skipping
  private final static int TERM_DOC_FREQ_RAND = 20;

  @BeforeClass
  public static void beforeClass() throws Exception {
    NUM_TEST_ITER = atLeast(20);
  }

  class FieldData implements Comparable {
    final FieldInfo fieldInfo;
    final TermData[] terms;
    final boolean omitTF;
    final boolean storePayloads;

    public FieldData(final String name, final FieldInfos fieldInfos, final TermData[] terms, final boolean omitTF, final boolean storePayloads) {
      this.omitTF = omitTF;
      this.storePayloads = storePayloads;
      fieldInfos.addOrUpdate(name, true);
      fieldInfo = fieldInfos.fieldInfo(name);
      // TODO: change this test to use all three
      fieldInfo.indexOptions = omitTF ? IndexOptions.DOCS_ONLY : IndexOptions.DOCS_AND_FREQS_AND_POSITIONS;
      fieldInfo.storePayloads = storePayloads;
      this.terms = terms;
      for(int i=0;i<terms.length;i++)
        terms[i].field = this;

      Arrays.sort(terms);
    }

    public int compareTo(final Object other) {
      return fieldInfo.name.compareTo(((FieldData) other).fieldInfo.name);
    }

    public void write(final FieldsConsumer consumer) throws Throwable {
      Arrays.sort(terms);
      final TermsConsumer termsConsumer = consumer.addField(fieldInfo);
      long sumTotalTermCount = 0;
      long sumDF = 0;
      OpenBitSet visitedDocs = new OpenBitSet();
      for (final TermData term : terms) {
        for (int i = 0; i < term.docs.length; i++) {
          visitedDocs.set(term.docs[i]);
        }
        sumDF += term.docs.length;
        sumTotalTermCount += term.write(termsConsumer);
      }
      termsConsumer.finish(sumTotalTermCount, sumDF, (int) visitedDocs.cardinality());
    }
  }

  class PositionData {
    int pos;
    BytesRef payload;

    PositionData(final int pos, final BytesRef payload) {
      this.pos = pos;
      this.payload = payload;
    }
  }

  class TermData implements Comparable {
    String text2;
    final BytesRef text;
    int[] docs;
    PositionData[][] positions;
    FieldData field;

    public TermData(final String text, final int[] docs, final PositionData[][] positions) {
      this.text = new BytesRef(text);
      this.text2 = text;
      this.docs = docs;
      this.positions = positions;
    }

    public int compareTo(final Object o) {
      return text.compareTo(((TermData) o).text);
    }

    public long write(final TermsConsumer termsConsumer) throws Throwable {
      final PostingsConsumer postingsConsumer = termsConsumer.startTerm(text);
      long totTF = 0;
      for(int i=0;i<docs.length;i++) {
        final int termDocFreq;
        if (field.omitTF) {
          termDocFreq = 0;
        } else {
          termDocFreq = positions[i].length;
        }
        postingsConsumer.startDoc(docs[i], termDocFreq);
        if (!field.omitTF) {
          totTF += positions[i].length;
          for(int j=0;j<positions[i].length;j++) {
            final PositionData pos = positions[i][j];
            postingsConsumer.addPosition(pos.pos, pos.payload);
          }
          postingsConsumer.finishDoc();
        }
      }
      termsConsumer.finishTerm(text, new TermStats(docs.length, totTF));
      return totTF;
    }
  }

  final private static String SEGMENT = "0";

  TermData[] makeRandomTerms(final boolean omitTF, final boolean storePayloads) {
    final int numTerms = 1+random.nextInt(NUM_TERMS_RAND);
    //final int numTerms = 2;
    final TermData[] terms = new TermData[numTerms];

    final HashSet<String> termsSeen = new HashSet<String>();

    for(int i=0;i<numTerms;i++) {

      // Make term text
      String text2;
      while(true) {
        text2 = _TestUtil.randomUnicodeString(random);
        if (!termsSeen.contains(text2) && !text2.endsWith(".")) {
          termsSeen.add(text2);
          break;
        }
      }

      final int docFreq = 1+random.nextInt(DOC_FREQ_RAND);
      final int[] docs = new int[docFreq];
      PositionData[][] positions;

      if (!omitTF)
        positions = new PositionData[docFreq][];
      else
        positions = null;

      int docID = 0;
      for(int j=0;j<docFreq;j++) {
        docID += _TestUtil.nextInt(random, 1, 10);
        docs[j] = docID;

        if (!omitTF) {
          final int termFreq = 1+random.nextInt(TERM_DOC_FREQ_RAND);
          positions[j] = new PositionData[termFreq];
          int position = 0;
          for(int k=0;k<termFreq;k++) {
            position += _TestUtil.nextInt(random, 1, 10);

            final BytesRef payload;
            if (storePayloads && random.nextInt(4) == 0) {
              final byte[] bytes = new byte[1+random.nextInt(5)];
              for(int l=0;l<bytes.length;l++) {
                bytes[l] = (byte) random.nextInt(255);
              }
              payload = new BytesRef(bytes);
            } else {
              payload = null;
            }

            positions[j][k] = new PositionData(position, payload);
          }
        }
      }

      terms[i] = new TermData(text2, docs, positions);
    }

    return terms;
  }

  public void testFixedPostings() throws Throwable {
    final int NUM_TERMS = 100;
    final TermData[] terms = new TermData[NUM_TERMS];
    for(int i=0;i<NUM_TERMS;i++) {
      final int[] docs = new int[] {i};
      final String text = Integer.toString(i, Character.MAX_RADIX);
      terms[i] = new TermData(text, docs, null);
    }

    final FieldInfos fieldInfos = new FieldInfos();

    final FieldData field = new FieldData("field", fieldInfos, terms, true, false);
    final FieldData[] fields = new FieldData[] {field};

    final Directory dir = newDirectory();
    FieldInfos clonedFieldInfos = (FieldInfos) fieldInfos.clone();
    this.write(fieldInfos, dir, fields, true);
    Codec codec = Codec.getDefault();
    final SegmentInfo si = new SegmentInfo(SEGMENT, 10000, dir, false, codec, clonedFieldInfos);

    final FieldsProducer reader = codec.postingsFormat().fieldsProducer(new SegmentReadState(dir, si, fieldInfos, newIOContext(random), IndexReader.DEFAULT_TERMS_INDEX_DIVISOR));

    final FieldsEnum fieldsEnum = reader.iterator();
    assertNotNull(fieldsEnum.next());
    final TermsEnum termsEnum = fieldsEnum.terms();

    DocsEnum docsEnum = null;
    for(int i=0;i<NUM_TERMS;i++) {
      final BytesRef term = termsEnum.next();
      assertNotNull(term);
      assertEquals(terms[i].text2, term.utf8ToString());

      // do this twice to stress test the codec's reuse, ie,
      // make sure it properly fully resets (rewinds) its
      // internal state:
      for(int iter=0;iter<2;iter++) {
        docsEnum = termsEnum.docs(null,  docsEnum);
        assertEquals(terms[i].docs[0], docsEnum.nextDoc());
        assertEquals(DocsEnum.NO_MORE_DOCS, docsEnum.nextDoc());
      }
    }
    assertNull(termsEnum.next());

    for(int i=0;i<NUM_TERMS;i++) {
      assertEquals(termsEnum.seekCeil(new BytesRef(terms[i].text2)), TermsEnum.SeekStatus.FOUND);
    }

    assertNull(fieldsEnum.next());
    reader.close();
    dir.close();
  }

  public void testRandomPostings() throws Throwable {
    final FieldInfos fieldInfos = new FieldInfos();

    final FieldData[] fields = new FieldData[NUM_FIELDS];
    for(int i=0;i<NUM_FIELDS;i++) {
      final boolean omitTF = 0==(i%3);
      final boolean storePayloads = 1==(i%3);
      fields[i] = new FieldData(fieldNames[i], fieldInfos, this.makeRandomTerms(omitTF, storePayloads), omitTF, storePayloads);
    }

    final Directory dir = newDirectory();

    if (VERBOSE) {
      System.out.println("TEST: now write postings");
    }

    FieldInfos clonedFieldInfos = (FieldInfos) fieldInfos.clone();
    this.write(fieldInfos, dir, fields, false);
    Codec codec = Codec.getDefault();
    final SegmentInfo si = new SegmentInfo(SEGMENT, 10000, dir, false, codec, clonedFieldInfos);

    if (VERBOSE) {
      System.out.println("TEST: now read postings");
    }
    final FieldsProducer terms = codec.postingsFormat().fieldsProducer(new SegmentReadState(dir, si, fieldInfos, newIOContext(random), IndexReader.DEFAULT_TERMS_INDEX_DIVISOR));

    final Verify[] threads = new Verify[NUM_TEST_THREADS-1];
    for(int i=0;i<NUM_TEST_THREADS-1;i++) {
      threads[i] = new Verify(si, fields, terms);
      threads[i].setDaemon(true);
      threads[i].start();
    }

    new Verify(si, fields, terms).run();

    for(int i=0;i<NUM_TEST_THREADS-1;i++) {
      threads[i].join();
      assert !threads[i].failed;
    }

    terms.close();
    dir.close();
  }

  public void testSepPositionAfterMerge() throws IOException {
    final Directory dir = newDirectory();
    final IndexWriterConfig config = newIndexWriterConfig(Version.LUCENE_31,
      new MockAnalyzer(random));
    config.setCodec(_TestUtil.alwaysFormat(new MockSepPostingsFormat()));
    final IndexWriter writer = new IndexWriter(dir, config);

    try {
      final PhraseQuery pq = new PhraseQuery();
      pq.add(new Term("content", "bbb"));
      pq.add(new Term("content", "ccc"));

      final Document doc = new Document();
      FieldType customType = new FieldType(TextField.TYPE_UNSTORED);
      customType.setOmitNorms(true);
      doc.add(newField("content", "aaa bbb ccc ddd", customType));

      // add document and force commit for creating a first segment
      writer.addDocument(doc);
      writer.commit();

      ScoreDoc[] results = this.search(writer, pq, 5);
      assertEquals(1, results.length);
      assertEquals(0, results[0].doc);

      // add document and force commit for creating a second segment
      writer.addDocument(doc);
      writer.commit();

      // at this point, there should be at least two segments
      results = this.search(writer, pq, 5);
      assertEquals(2, results.length);
      assertEquals(0, results[0].doc);

      writer.optimize();

      // optimise to merge the segments.
      results = this.search(writer, pq, 5);
      assertEquals(2, results.length);
      assertEquals(0, results[0].doc);
    }
    finally {
      writer.close();
      dir.close();
    }
  }

  private ScoreDoc[] search(final IndexWriter writer, final Query q, final int n) throws IOException {
    final IndexReader reader = writer.getReader();
    final IndexSearcher searcher = newSearcher(reader);
    try {
      return searcher.search(q, null, n).scoreDocs;
    }
    finally {
      searcher.close();
      reader.close();
    }
  }

  private class Verify extends Thread {
    final Fields termsDict;
    final FieldData[] fields;
    final SegmentInfo si;
    volatile boolean failed;

    Verify(final SegmentInfo si, final FieldData[] fields, final Fields termsDict) {
      this.fields = fields;
      this.termsDict = termsDict;
      this.si = si;
    }

    @Override
    public void run() {
      try {
        this._run();
      } catch (final Throwable t) {
        failed = true;
        throw new RuntimeException(t);
      }
    }

    private void verifyDocs(final int[] docs, final PositionData[][] positions, final DocsEnum docsEnum, final boolean doPos) throws Throwable {
      for(int i=0;i<docs.length;i++) {
        final int doc = docsEnum.nextDoc();
        assertTrue(doc != DocIdSetIterator.NO_MORE_DOCS);
        assertEquals(docs[i], doc);
        if (doPos) {
          this.verifyPositions(positions[i], ((DocsAndPositionsEnum) docsEnum));
        }
      }
      assertEquals(DocIdSetIterator.NO_MORE_DOCS, docsEnum.nextDoc());
    }

    byte[] data = new byte[10];

    private void verifyPositions(final PositionData[] positions, final DocsAndPositionsEnum posEnum) throws Throwable {
      for(int i=0;i<positions.length;i++) {
        final int pos = posEnum.nextPosition();
        assertEquals(positions[i].pos, pos);
        if (positions[i].payload != null) {
          assertTrue(posEnum.hasPayload());
          if (TestCodecs.random.nextInt(3) < 2) {
            // Verify the payload bytes
            final BytesRef otherPayload = posEnum.getPayload();
            assertTrue("expected=" + positions[i].payload.toString() + " got=" + otherPayload.toString(), positions[i].payload.equals(otherPayload));
          }
        } else {
          assertFalse(posEnum.hasPayload());
        }
      }
    }

    public void _run() throws Throwable {

      for(int iter=0;iter<NUM_TEST_ITER;iter++) {
        final FieldData field = fields[TestCodecs.random.nextInt(fields.length)];
        final TermsEnum termsEnum = termsDict.terms(field.fieldInfo.name).iterator();
        if (si.getCodec() instanceof Lucene3xCodec) {
          // code below expects unicode sort order
          continue;
        }

        int upto = 0;
        // Test straight enum of the terms:
        while(true) {
          final BytesRef term = termsEnum.next();
          if (term == null) {
            break;
          }
          final BytesRef expected = new BytesRef(field.terms[upto++].text2);
          assertTrue("expected=" + expected + " vs actual " + term, expected.bytesEquals(term));
        }
        assertEquals(upto, field.terms.length);

        // Test random seek:
        TermData term = field.terms[TestCodecs.random.nextInt(field.terms.length)];
        TermsEnum.SeekStatus status = termsEnum.seekCeil(new BytesRef(term.text2));
        assertEquals(status, TermsEnum.SeekStatus.FOUND);
        assertEquals(term.docs.length, termsEnum.docFreq());
        if (field.omitTF) {
          this.verifyDocs(term.docs, term.positions, termsEnum.docs(null, null), false);
        } else {
          this.verifyDocs(term.docs, term.positions, termsEnum.docsAndPositions(null, null), true);
        }

        // Test random seek by ord:
        final int idx = TestCodecs.random.nextInt(field.terms.length);
        term = field.terms[idx];
        boolean success = false;
        try {
          termsEnum.seekExact(idx);
          success = true;
        } catch (UnsupportedOperationException uoe) {
          // ok -- skip it
        }
        if (success) {
          assertEquals(status, TermsEnum.SeekStatus.FOUND);
          assertTrue(termsEnum.term().bytesEquals(new BytesRef(term.text2)));
          assertEquals(term.docs.length, termsEnum.docFreq());
          if (field.omitTF) {
            this.verifyDocs(term.docs, term.positions, termsEnum.docs(null, null), false);
          } else {
            this.verifyDocs(term.docs, term.positions, termsEnum.docsAndPositions(null, null), true);
          }
        }

        // Test seek to non-existent terms:
        if (VERBOSE) {
          System.out.println("TEST: seek non-exist terms");
        }
        for(int i=0;i<100;i++) {
          final String text2 = _TestUtil.randomUnicodeString(random) + ".";
          status = termsEnum.seekCeil(new BytesRef(text2));
          assertTrue(status == TermsEnum.SeekStatus.NOT_FOUND ||
                     status == TermsEnum.SeekStatus.END);
        }

        // Seek to each term, backwards:
        if (VERBOSE) {
          System.out.println("TEST: seek terms backwards");
        }
        for(int i=field.terms.length-1;i>=0;i--) {
          assertEquals(Thread.currentThread().getName() + ": field=" + field.fieldInfo.name + " term=" + field.terms[i].text2, TermsEnum.SeekStatus.FOUND, termsEnum.seekCeil(new BytesRef(field.terms[i].text2)));
          assertEquals(field.terms[i].docs.length, termsEnum.docFreq());
        }

        // Seek to each term by ord, backwards
        for(int i=field.terms.length-1;i>=0;i--) {
          try {
            termsEnum.seekExact(i);
            assertEquals(field.terms[i].docs.length, termsEnum.docFreq());
            assertTrue(termsEnum.term().bytesEquals(new BytesRef(field.terms[i].text2)));
          } catch (UnsupportedOperationException uoe) {
          }
        }

        // Seek to non-existent empty-string term
        status = termsEnum.seekCeil(new BytesRef(""));
        assertNotNull(status);
        //assertEquals(TermsEnum.SeekStatus.NOT_FOUND, status);

        // Make sure we're now pointing to first term
        assertTrue(termsEnum.term().bytesEquals(new BytesRef(field.terms[0].text2)));

        // Test docs enum
        termsEnum.seekCeil(new BytesRef(""));
        upto = 0;
        do {
          term = field.terms[upto];
          if (TestCodecs.random.nextInt(3) == 1) {
            final DocsEnum docs = termsEnum.docs(null, null);
            final DocsAndPositionsEnum postings = termsEnum.docsAndPositions(null, null);

            final DocsEnum docsEnum;
            if (postings != null) {
              docsEnum = postings;
            } else {
              docsEnum = docs;
            }
            int upto2 = -1;
            while(upto2 < term.docs.length-1) {
              // Maybe skip:
              final int left = term.docs.length-upto2;
              int doc;
              if (TestCodecs.random.nextInt(3) == 1 && left >= 1) {
                final int inc = 1+TestCodecs.random.nextInt(left-1);
                upto2 += inc;
                if (TestCodecs.random.nextInt(2) == 1) {
                  doc = docsEnum.advance(term.docs[upto2]);
                  assertEquals(term.docs[upto2], doc);
                } else {
                  doc = docsEnum.advance(1+term.docs[upto2]);
                  if (doc == DocIdSetIterator.NO_MORE_DOCS) {
                    // skipped past last doc
                    assert upto2 == term.docs.length-1;
                    break;
                  } else {
                    // skipped to next doc
                    assert upto2 < term.docs.length-1;
                    if (doc >= term.docs[1+upto2]) {
                      upto2++;
                    }
                  }
                }
              } else {
                doc = docsEnum.nextDoc();
                assertTrue(doc != -1);
                upto2++;
              }
              assertEquals(term.docs[upto2], doc);
              if (!field.omitTF) {
                assertEquals(term.positions[upto2].length, docsEnum.freq());
                if (TestCodecs.random.nextInt(2) == 1) {
                  this.verifyPositions(term.positions[upto2], postings);
                }
              }
            }

            assertEquals(DocIdSetIterator.NO_MORE_DOCS, docsEnum.nextDoc());
          }
          upto++;

        } while (termsEnum.next() != null);

        assertEquals(upto, field.terms.length);
      }
    }
  }

  private void write(final FieldInfos fieldInfos, final Directory dir, final FieldData[] fields, boolean allowPreFlex) throws Throwable {

    final int termIndexInterval = _TestUtil.nextInt(random, 13, 27);
    final Codec codec = Codec.getDefault();
    final SegmentWriteState state = new SegmentWriteState(null, dir, SEGMENT, fieldInfos, 10000, termIndexInterval, codec, null, newIOContext(random));

    final FieldsConsumer consumer = codec.postingsFormat().fieldsConsumer(state);
    Arrays.sort(fields);
    for (final FieldData field : fields) {
      if (!allowPreFlex && codec instanceof Lucene3xCodec) {
        // code below expects unicode sort order
        continue;
      }
      field.write(consumer);
    }
    consumer.close();
  }
}
