/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.index.mapper;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.StopFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.IndexableFieldType;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.queries.spans.FieldMaskingSpanQuery;
import org.apache.lucene.queries.spans.SpanNearQuery;
import org.apache.lucene.queries.spans.SpanOrQuery;
import org.apache.lucene.queries.spans.SpanTermQuery;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MultiPhraseQuery;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SynonymQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.analysis.CannedTokenStream;
import org.apache.lucene.tests.analysis.MockSynonymAnalyzer;
import org.apache.lucene.tests.analysis.Token;
import org.apache.lucene.util.BytesRef;
import org.opensearch.common.lucene.search.MultiPhrasePrefixQuery;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.core.common.Strings;
import org.opensearch.core.xcontent.MediaTypeRegistry;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.index.IndexSettings;
import org.opensearch.index.analysis.AnalyzerScope;
import org.opensearch.index.analysis.CharFilterFactory;
import org.opensearch.index.analysis.CustomAnalyzer;
import org.opensearch.index.analysis.IndexAnalyzers;
import org.opensearch.index.analysis.NamedAnalyzer;
import org.opensearch.index.analysis.StandardTokenizerFactory;
import org.opensearch.index.analysis.TokenFilterFactory;
import org.opensearch.index.mapper.TextFieldMapper.TextFieldType;
import org.opensearch.index.query.MatchPhrasePrefixQueryBuilder;
import org.opensearch.index.query.MatchPhraseQueryBuilder;
import org.opensearch.index.query.QueryShardContext;
import org.opensearch.index.search.MatchQuery;
import org.junit.Before;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.core.Is.is;

public class TextFieldMapperTests extends MapperTestCase {

    public String textFieldName = "text";

    @Before
    public void setup() {
        textFieldName = "text";
    }

    @Override
    protected void writeFieldValue(XContentBuilder builder) throws IOException {
        builder.value(1234);
    }

    @Override
    protected void assertParseMaximalWarnings() {
        assertWarnings("Parameter [boost] on field [field] is deprecated and will be removed in 3.0");
    }

    public final void testExistsQueryIndexDisabled() throws IOException {
        MapperService mapperService = createMapperService(fieldMapping(b -> {
            minimalMapping(b);
            b.field("index", false);
            b.field("norms", false);
        }));
        assertExistsQuery(mapperService);
        assertParseMinimalWarnings();
    }

    public final void testExistsQueryIndexDisabledStoreTrue() throws IOException {
        MapperService mapperService = createMapperService(fieldMapping(b -> {
            minimalMapping(b);
            b.field("index", false);
            b.field("norms", false);
            b.field("store", true);
        }));
        assertExistsQuery(mapperService);
        assertParseMinimalWarnings();
    }

    public final void testExistsQueryWithNorms() throws IOException {
        MapperService mapperService = createMapperService(fieldMapping(b -> {
            minimalMapping(b);
            b.field("norms", false);
        }));
        assertExistsQuery(mapperService);
        assertParseMinimalWarnings();
    }

    @Override
    protected void registerParameters(ParameterChecker checker) throws IOException {
        checker.registerUpdateCheck(b -> b.field("fielddata", true), m -> {
            TextFieldType ft = (TextFieldType) m.fieldType();
            assertTrue(ft.fielddata());
        });
        checker.registerUpdateCheck(b -> {
            b.field("fielddata", true);
            b.startObject("fielddata_frequency_filter");
            {
                b.field("min", 10);
                b.field("max", 20);
                b.field("min_segment_size", 100);
            }
            b.endObject();
        }, m -> {
            TextFieldType ft = (TextFieldType) m.fieldType();
            assertEquals(10, ft.fielddataMinFrequency(), 0);
            assertEquals(20, ft.fielddataMaxFrequency(), 0);
            assertEquals(100, ft.fielddataMinSegmentSize());
        });
        checker.registerUpdateCheck(b -> b.field("eager_global_ordinals", "true"), m -> assertTrue(m.fieldType().eagerGlobalOrdinals()));
        checker.registerUpdateCheck(b -> {
            b.field("analyzer", "default");
            b.field("search_analyzer", "keyword");
        }, m -> assertEquals("keyword", m.fieldType().getTextSearchInfo().getSearchAnalyzer().name()));
        checker.registerUpdateCheck(b -> {
            b.field("analyzer", "default");
            b.field("search_analyzer", "keyword");
            b.field("search_quote_analyzer", "keyword");
        }, m -> assertEquals("keyword", m.fieldType().getTextSearchInfo().getSearchQuoteAnalyzer().name()));

        checker.registerConflictCheck("index", b -> b.field("index", false));
        checker.registerConflictCheck("store", b -> b.field("store", true));
        if (!textFieldName.equals("match_only_text")) {
            checker.registerConflictCheck("index_phrases", b -> b.field("index_phrases", true));
            checker.registerConflictCheck("index_prefixes", b -> b.startObject("index_prefixes").endObject());
            checker.registerConflictCheck("index_options", b -> b.field("index_options", "docs"));
        }
        checker.registerConflictCheck("similarity", b -> b.field("similarity", "boolean"));
        checker.registerConflictCheck("analyzer", b -> b.field("analyzer", "keyword"));
        checker.registerConflictCheck("term_vector", b -> b.field("term_vector", "yes"));

        checker.registerConflictCheck("position_increment_gap", b -> b.field("position_increment_gap", 10));

        if (!textFieldName.equals(MatchOnlyTextFieldMapper.CONTENT_TYPE)) {
            // norms can be set from true to false, but not vice versa
            checker.registerConflictCheck("norms", fieldMapping(b -> {
                b.field("type", textFieldName);
                b.field("norms", false);
            }), fieldMapping(b -> {
                b.field("type", textFieldName);
                b.field("norms", true);
            }));
            checker.registerUpdateCheck(b -> {
                b.field("type", textFieldName);
                b.field("norms", true);
            }, b -> {
                b.field("type", textFieldName);
                b.field("norms", false);
            }, m -> assertFalse(m.fieldType().getTextSearchInfo().hasNorms()));
        }

        checker.registerUpdateCheck(b -> b.field("boost", 2.0), m -> assertEquals(m.fieldType().boost(), 2.0, 0));

    }

    @Override
    protected IndexAnalyzers createIndexAnalyzers(IndexSettings indexSettings) {
        NamedAnalyzer dflt = new NamedAnalyzer(
            "default",
            AnalyzerScope.INDEX,
            new StandardAnalyzer(),
            TextFieldMapper.Defaults.POSITION_INCREMENT_GAP
        );
        NamedAnalyzer standard = new NamedAnalyzer("standard", AnalyzerScope.INDEX, new StandardAnalyzer());
        NamedAnalyzer keyword = new NamedAnalyzer("keyword", AnalyzerScope.INDEX, new KeywordAnalyzer());
        NamedAnalyzer whitespace = new NamedAnalyzer("whitespace", AnalyzerScope.INDEX, new WhitespaceAnalyzer());
        NamedAnalyzer stop = new NamedAnalyzer(
            "my_stop_analyzer",
            AnalyzerScope.INDEX,
            new CustomAnalyzer(
                new StandardTokenizerFactory(indexSettings, null, "standard", indexSettings.getSettings()),
                new CharFilterFactory[0],
                new TokenFilterFactory[] { new TokenFilterFactory() {
                    @Override
                    public String name() {
                        return "stop";
                    }

                    @Override
                    public TokenStream create(TokenStream tokenStream) {
                        return new StopFilter(tokenStream, EnglishAnalyzer.ENGLISH_STOP_WORDS_SET);
                    }
                } }
            )
        );
        return new IndexAnalyzers(
            Map.of("default", dflt, "standard", standard, "keyword", keyword, "whitespace", whitespace, "my_stop_analyzer", stop),
            Map.of(),
            Map.of()
        );
    }

    @Override
    protected void minimalMapping(XContentBuilder b) throws IOException {
        b.field("type", textFieldName);
    }

    public void testDefaults() throws IOException {
        DocumentMapper mapper = createDocumentMapper(fieldMapping(this::minimalMapping));
        assertEquals(fieldMapping(this::minimalMapping).toString(), mapper.mappingSource().toString());

        ParsedDocument doc = mapper.parse(source(b -> b.field("field", "1234")));
        IndexableField[] fields = doc.rootDoc().getFields("field");
        assertEquals(1, fields.length);
        assertEquals("1234", fields[0].stringValue());
        IndexableFieldType fieldType = fields[0].fieldType();
        assertThat(fieldType.omitNorms(), equalTo(false));
        assertTrue(fieldType.tokenized());
        assertFalse(fieldType.stored());
        assertThat(fieldType.indexOptions(), equalTo(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS));
        assertThat(fieldType.storeTermVectors(), equalTo(false));
        assertThat(fieldType.storeTermVectorOffsets(), equalTo(false));
        assertThat(fieldType.storeTermVectorPositions(), equalTo(false));
        assertThat(fieldType.storeTermVectorPayloads(), equalTo(false));
        assertEquals(DocValuesType.NONE, fieldType.docValuesType());
    }

    public void testBWCSerialization() throws IOException {
        MapperService mapperService = createMapperService(fieldMapping(b -> {
            b.field("type", textFieldName);
            b.field("fielddata", true);
            b.startObject("fields");
            {
                b.startObject("subfield").field("type", "long").endObject();
            }
            b.endObject();
            b.field("store", true);
            b.field("similarity", "BM25");
            b.field("index_options", "offsets");
            b.field("norms", false);
            b.field("term_vector", "yes");
            b.field("position_increment_gap", 0);
            b.startObject("fielddata_frequency_filter");
            {
                b.field("min", 0.001);
                b.field("max", 0.1);
                b.field("min_segment_size", 500);
            }
            b.endObject();
            b.field("eager_global_ordinals", true);
            b.field("index_phrases", true);
            b.startObject("index_prefixes");
            {
                b.field("min_chars", 1);
                b.field("max_chars", 10);
            }
            b.endObject();
            b.startObject("meta");
            {
                b.field("unit", "min");
            }
            b.endObject();
            b.startArray("copy_to");
            {
                b.value("target");
            }
            b.endArray();
        }));
        assertEquals(
            "{\"_doc\":{\"properties\":{\"field\":{\"type\":\"text\",\"store\":true,\"fields\":{\"subfield\":{\"type\":\"long\"}},"
                + "\"copy_to\":[\"target\"],\"meta\":{\"unit\":\"min\"},\"index_options\":\"offsets\",\"term_vector\":\"yes\",\"norms\":false,"
                + "\"similarity\":\"BM25\",\"eager_global_ordinals\":true,\"position_increment_gap\":0,"
                + "\"fielddata\":true,\"fielddata_frequency_filter\":{\"min\":0.001,\"max\":0.1,\"min_segment_size\":500},"
                + "\"index_prefixes\":{\"min_chars\":1,\"max_chars\":10},\"index_phrases\":true}}}}",
            Strings.toString(MediaTypeRegistry.JSON, mapperService.documentMapper())
        );
    }

    public void testEnableStore() throws IOException {
        DocumentMapper mapper = createDocumentMapper(fieldMapping(b -> b.field("type", textFieldName).field("store", true)));
        ParsedDocument doc = mapper.parse(source(b -> b.field("field", "1234")));
        IndexableField[] fields = doc.rootDoc().getFields("field");
        assertEquals(1, fields.length);
        assertTrue(fields[0].fieldType().stored());
    }

    public void testDisableIndex() throws IOException {
        DocumentMapper mapper = createDocumentMapper(fieldMapping(b -> b.field("type", textFieldName).field("index", false)));
        ParsedDocument doc = mapper.parse(source(b -> b.field("field", "1234")));
        IndexableField[] fields = doc.rootDoc().getFields("field");
        assertEquals(0, fields.length);
    }

    public void testDisableNorms() throws IOException {
        DocumentMapper mapper = createDocumentMapper(fieldMapping(b -> b.field("type", textFieldName).field("norms", false)));
        ParsedDocument doc = mapper.parse(source(b -> b.field("field", "1234")));
        IndexableField[] fields = doc.rootDoc().getFields("field");
        assertEquals(1, fields.length);
        assertTrue(fields[0].fieldType().omitNorms());
    }

    public void testIndexOptions() throws IOException {
        Map<String, IndexOptions> supportedOptions = new HashMap<>();
        supportedOptions.put("docs", IndexOptions.DOCS);
        supportedOptions.put("freqs", IndexOptions.DOCS_AND_FREQS);
        supportedOptions.put("positions", IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
        supportedOptions.put("offsets", IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);

        XContentBuilder mapping = MediaTypeRegistry.JSON.contentBuilder().startObject().startObject("_doc").startObject("properties");
        for (String option : supportedOptions.keySet()) {
            mapping.startObject(option).field("type", textFieldName).field("index_options", option).endObject();
        }
        mapping.endObject().endObject().endObject();

        DocumentMapper mapper = createDocumentMapper(mapping);
        String serialized = Strings.toString(MediaTypeRegistry.JSON, mapper);
        assertThat(serialized, containsString("\"offsets\":{\"type\":\"text\",\"index_options\":\"offsets\"}"));
        assertThat(serialized, containsString("\"freqs\":{\"type\":\"text\",\"index_options\":\"freqs\"}"));
        assertThat(serialized, containsString("\"docs\":{\"type\":\"text\",\"index_options\":\"docs\"}"));

        ParsedDocument doc = mapper.parse(source(b -> {
            for (String option : supportedOptions.keySet()) {
                b.field(option, "1234");
            }
        }));

        for (Map.Entry<String, IndexOptions> entry : supportedOptions.entrySet()) {
            String field = entry.getKey();
            IndexOptions options = entry.getValue();
            IndexableField[] fields = doc.rootDoc().getFields(field);
            assertEquals(1, fields.length);
            assertEquals(options, fields[0].fieldType().indexOptions());
        }
    }

    public void testPositionIncrementGapOnIndexPrefixField() throws IOException {
        // test default position_increment_gap
        MapperService mapperService = createMapperService(
            fieldMapping(b -> b.field("type", "text").field("analyzer", "default").startObject("index_prefixes").endObject())
        );
        ParsedDocument doc = mapperService.documentMapper().parse(source(b -> b.array("field", new String[] { "a", "b 12" })));

        withLuceneIndex(mapperService, iw -> iw.addDocument(doc.rootDoc()), reader -> {
            TermsEnum terms = getOnlyLeafReader(reader).terms("field").iterator();
            assertTrue(terms.seekExact(new BytesRef("12")));
            PostingsEnum postings = terms.postings(null, PostingsEnum.POSITIONS);
            assertEquals(0, postings.nextDoc());
            assertEquals(TextFieldMapper.Defaults.POSITION_INCREMENT_GAP + 2, postings.nextPosition());
        });

        withLuceneIndex(mapperService, iw -> iw.addDocument(doc.rootDoc()), reader -> {
            TermsEnum terms = getOnlyLeafReader(reader).terms("field._index_prefix").iterator();
            assertTrue(terms.seekExact(new BytesRef("12")));
            PostingsEnum postings = terms.postings(null, PostingsEnum.POSITIONS);
            assertEquals(0, postings.nextDoc());
            assertEquals(TextFieldMapper.Defaults.POSITION_INCREMENT_GAP + 2, postings.nextPosition());
        });

        // test custom position_increment_gap
        final int positionIncrementGap = randomIntBetween(1, 1000);
        MapperService mapperService2 = createMapperService(
            fieldMapping(
                b -> b.field("type", "text")
                    .field("position_increment_gap", positionIncrementGap)
                    .field("analyzer", "default")
                    .startObject("index_prefixes")
                    .endObject()
            )
        );
        ParsedDocument doc2 = mapperService2.documentMapper().parse(source(b -> b.array("field", new String[] { "a", "b 12" })));
        withLuceneIndex(mapperService2, iw -> iw.addDocument(doc2.rootDoc()), reader -> {
            TermsEnum terms = getOnlyLeafReader(reader).terms("field").iterator();
            assertTrue(terms.seekExact(new BytesRef("12")));
            PostingsEnum postings = terms.postings(null, PostingsEnum.POSITIONS);
            assertEquals(0, postings.nextDoc());
            assertEquals(positionIncrementGap + 2, postings.nextPosition());
        });
        withLuceneIndex(mapperService2, iw -> iw.addDocument(doc2.rootDoc()), reader -> {
            TermsEnum terms = getOnlyLeafReader(reader).terms("field._index_prefix").iterator();
            assertTrue(terms.seekExact(new BytesRef("12")));
            PostingsEnum postings = terms.postings(null, PostingsEnum.POSITIONS);
            assertEquals(0, postings.nextDoc());
            assertEquals(positionIncrementGap + 2, postings.nextPosition());
        });
    }

    public void testDefaultPositionIncrementGap() throws IOException {
        MapperService mapperService = createMapperService(fieldMapping(this::minimalMapping));
        ParsedDocument doc = mapperService.documentMapper().parse(source(b -> b.array("field", new String[] { "a", "b" })));

        IndexableField[] fields = doc.rootDoc().getFields("field");
        assertEquals(2, fields.length);
        assertEquals("a", fields[0].stringValue());
        assertEquals("b", fields[1].stringValue());

        withLuceneIndex(mapperService, iw -> iw.addDocument(doc.rootDoc()), reader -> {
            TermsEnum terms = getOnlyLeafReader(reader).terms("field").iterator();
            assertTrue(terms.seekExact(new BytesRef("b")));
            PostingsEnum postings = terms.postings(null, PostingsEnum.POSITIONS);
            assertEquals(0, postings.nextDoc());
            assertEquals(TextFieldMapper.Defaults.POSITION_INCREMENT_GAP + 1, postings.nextPosition());
        });
    }

    public void testPositionIncrementGap() throws IOException {
        final int positionIncrementGap = randomIntBetween(1, 1000);
        MapperService mapperService = createMapperService(
            fieldMapping(b -> b.field("type", textFieldName).field("position_increment_gap", positionIncrementGap))
        );
        ParsedDocument doc = mapperService.documentMapper().parse(source(b -> b.array("field", new String[] { "a", "b" })));

        IndexableField[] fields = doc.rootDoc().getFields("field");
        assertEquals(2, fields.length);
        assertEquals("a", fields[0].stringValue());
        assertEquals("b", fields[1].stringValue());

        withLuceneIndex(mapperService, iw -> iw.addDocument(doc.rootDoc()), reader -> {
            TermsEnum terms = getOnlyLeafReader(reader).terms("field").iterator();
            assertTrue(terms.seekExact(new BytesRef("b")));
            PostingsEnum postings = terms.postings(null, PostingsEnum.POSITIONS);
            assertEquals(0, postings.nextDoc());
            assertEquals(positionIncrementGap + 1, postings.nextPosition());
        });
    }

    public void testSearchAnalyzerSerialization() throws IOException {
        XContentBuilder mapping = fieldMapping(
            b -> b.field("type", textFieldName).field("analyzer", "standard").field("search_analyzer", "keyword")
        );
        assertEquals(mapping.toString(), createDocumentMapper(mapping).mappingSource().toString());

        // special case: default index analyzer
        mapping = fieldMapping(b -> b.field("type", textFieldName).field("analyzer", "default").field("search_analyzer", "keyword"));
        assertEquals(mapping.toString(), createDocumentMapper(mapping).mappingSource().toString());

        // special case: default search analyzer
        mapping = fieldMapping(b -> b.field("type", textFieldName).field("analyzer", "keyword").field("search_analyzer", "default"));
        assertEquals(mapping.toString(), createDocumentMapper(mapping).mappingSource().toString());

        XContentBuilder builder = MediaTypeRegistry.JSON.contentBuilder();
        builder.startObject();
        createDocumentMapper(fieldMapping(this::minimalMapping)).toXContent(
            builder,
            new ToXContent.MapParams(Collections.singletonMap("include_defaults", "true"))
        );
        builder.endObject();
        String mappingString = builder.toString();
        assertTrue(mappingString.contains("analyzer"));
        assertTrue(mappingString.contains("search_analyzer"));
        assertTrue(mappingString.contains("search_quote_analyzer"));
    }

    public void testSearchQuoteAnalyzerSerialization() throws IOException {
        XContentBuilder mapping = fieldMapping(
            b -> b.field("type", textFieldName)
                .field("analyzer", "standard")
                .field("search_analyzer", "standard")
                .field("search_quote_analyzer", "keyword")
        );
        assertEquals(mapping.toString(), createDocumentMapper(mapping).mappingSource().toString());

        // special case: default index/search analyzer
        mapping = fieldMapping(
            b -> b.field("type", textFieldName)
                .field("analyzer", "default")
                .field("search_analyzer", "default")
                .field("search_quote_analyzer", "keyword")
        );
        assertEquals(mapping.toString(), createDocumentMapper(mapping).mappingSource().toString());
    }

    public void testTermVectors() throws IOException {
        XContentBuilder mapping = mapping(
            b -> b.startObject("field1")
                .field("type", textFieldName)
                .field("term_vector", "no")
                .endObject()
                .startObject("field2")
                .field("type", textFieldName)
                .field("term_vector", "yes")
                .endObject()
                .startObject("field3")
                .field("type", textFieldName)
                .field("term_vector", "with_offsets")
                .endObject()
                .startObject("field4")
                .field("type", textFieldName)
                .field("term_vector", "with_positions")
                .endObject()
                .startObject("field5")
                .field("type", textFieldName)
                .field("term_vector", "with_positions_offsets")
                .endObject()
                .startObject("field6")
                .field("type", textFieldName)
                .field("term_vector", "with_positions_offsets_payloads")
                .endObject()
        );

        DocumentMapper defaultMapper = createDocumentMapper(mapping);

        ParsedDocument doc = defaultMapper.parse(
            source(
                b -> b.field("field1", "1234")
                    .field("field2", "1234")
                    .field("field3", "1234")
                    .field("field4", "1234")
                    .field("field5", "1234")
                    .field("field6", "1234")
            )
        );

        assertThat(doc.rootDoc().getField("field1").fieldType().storeTermVectors(), equalTo(false));
        assertThat(doc.rootDoc().getField("field1").fieldType().storeTermVectorOffsets(), equalTo(false));
        assertThat(doc.rootDoc().getField("field1").fieldType().storeTermVectorPositions(), equalTo(false));
        assertThat(doc.rootDoc().getField("field1").fieldType().storeTermVectorPayloads(), equalTo(false));

        assertThat(doc.rootDoc().getField("field2").fieldType().storeTermVectors(), equalTo(true));
        assertThat(doc.rootDoc().getField("field2").fieldType().storeTermVectorOffsets(), equalTo(false));
        assertThat(doc.rootDoc().getField("field2").fieldType().storeTermVectorPositions(), equalTo(false));
        assertThat(doc.rootDoc().getField("field2").fieldType().storeTermVectorPayloads(), equalTo(false));

        assertThat(doc.rootDoc().getField("field3").fieldType().storeTermVectors(), equalTo(true));
        assertThat(doc.rootDoc().getField("field3").fieldType().storeTermVectorOffsets(), equalTo(true));
        assertThat(doc.rootDoc().getField("field3").fieldType().storeTermVectorPositions(), equalTo(false));
        assertThat(doc.rootDoc().getField("field3").fieldType().storeTermVectorPayloads(), equalTo(false));

        assertThat(doc.rootDoc().getField("field4").fieldType().storeTermVectors(), equalTo(true));
        assertThat(doc.rootDoc().getField("field4").fieldType().storeTermVectorOffsets(), equalTo(false));
        assertThat(doc.rootDoc().getField("field4").fieldType().storeTermVectorPositions(), equalTo(true));
        assertThat(doc.rootDoc().getField("field4").fieldType().storeTermVectorPayloads(), equalTo(false));

        assertThat(doc.rootDoc().getField("field5").fieldType().storeTermVectors(), equalTo(true));
        assertThat(doc.rootDoc().getField("field5").fieldType().storeTermVectorOffsets(), equalTo(true));
        assertThat(doc.rootDoc().getField("field5").fieldType().storeTermVectorPositions(), equalTo(true));
        assertThat(doc.rootDoc().getField("field5").fieldType().storeTermVectorPayloads(), equalTo(false));

        assertThat(doc.rootDoc().getField("field6").fieldType().storeTermVectors(), equalTo(true));
        assertThat(doc.rootDoc().getField("field6").fieldType().storeTermVectorOffsets(), equalTo(true));
        assertThat(doc.rootDoc().getField("field6").fieldType().storeTermVectorPositions(), equalTo(true));
        assertThat(doc.rootDoc().getField("field6").fieldType().storeTermVectorPayloads(), equalTo(true));
    }

    public void testEagerGlobalOrdinals() throws IOException {
        DocumentMapper mapper = createDocumentMapper(
            fieldMapping(b -> b.field("type", textFieldName).field("eager_global_ordinals", true))
        );

        FieldMapper fieldMapper = (FieldMapper) mapper.mappers().getMapper("field");
        assertTrue(fieldMapper.fieldType().eagerGlobalOrdinals());
    }

    public void testFielddata() throws IOException {
        MapperService disabledMapper = createMapperService(fieldMapping(this::minimalMapping));
        Exception e = expectThrows(IllegalArgumentException.class, () -> disabledMapper.fieldType("field").fielddataBuilder("test", () -> {
            throw new UnsupportedOperationException();
        }));
        assertThat(e.getMessage(), containsString("Text fields are not optimised for operations that require per-document field data"));

        MapperService enabledMapper = createMapperService(fieldMapping(b -> b.field("type", textFieldName).field("fielddata", true)));
        enabledMapper.fieldType("field").fielddataBuilder("test", () -> { throw new UnsupportedOperationException(); }); // no exception
                                                                                                                         // this time

        e = expectThrows(
            MapperParsingException.class,
            () -> createMapperService(fieldMapping(b -> b.field("type", textFieldName).field("index", false).field("fielddata", true)))
        );
        assertThat(e.getMessage(), containsString("Cannot enable fielddata on a [text] field that is not indexed"));
    }

    public void testFrequencyFilter() throws IOException {
        MapperService mapperService = createMapperService(
            fieldMapping(
                b -> b.field("type", textFieldName)
                    .field("fielddata", true)
                    .startObject("fielddata_frequency_filter")
                    .field("min", 2d)
                    .field("min_segment_size", 1000)
                    .endObject()
            )
        );
        TextFieldType fieldType = (TextFieldType) mapperService.fieldType("field");

        assertThat(fieldType.fielddataMinFrequency(), equalTo(2d));
        assertThat(fieldType.fielddataMaxFrequency(), equalTo((double) Integer.MAX_VALUE));
        assertThat(fieldType.fielddataMinSegmentSize(), equalTo(1000));
    }

    public void testNullConfigValuesFail() throws MapperParsingException {
        Exception e = expectThrows(
            MapperParsingException.class,
            () -> createDocumentMapper(fieldMapping(b -> b.field("type", textFieldName).field("analyzer", (String) null)))
        );
        assertThat(
            e.getMessage(),
            containsString("[analyzer] on mapper [field] of type [" + textFieldName + "] must not have a [null] value")
        );
    }

    public void testNotIndexedFieldPositionIncrement() {
        Exception e = expectThrows(
            MapperParsingException.class,
            () -> createDocumentMapper(
                fieldMapping(b -> b.field("type", textFieldName).field("index", false).field("position_increment_gap", 10))
            )
        );
        assertThat(e.getMessage(), containsString("Cannot set position_increment_gap on field [field]"));
    }

    public void testAnalyzedFieldPositionIncrementWithoutPositions() {
        for (String indexOptions : Arrays.asList("docs", "freqs")) {
            Exception e = expectThrows(
                MapperParsingException.class,
                () -> createDocumentMapper(
                    fieldMapping(
                        b -> b.field("type", textFieldName).field("index_options", indexOptions).field("position_increment_gap", 10)
                    )
                )
            );
            assertThat(e.getMessage(), containsString("Cannot set position_increment_gap on field [field] without positions enabled"));
        }
    }

    public void testIndexPrefixIndexTypes() throws IOException {
        {
            DocumentMapper mapper = createDocumentMapper(
                fieldMapping(
                    b -> b.field("type", textFieldName)
                        .field("analyzer", "standard")
                        .startObject("index_prefixes")
                        .endObject()
                        .field("index_options", "offsets")
                )
            );
            FieldMapper prefix = (FieldMapper) mapper.mappers().getMapper("field._index_prefix");
            assertEquals(prefix.name(), "field._index_prefix");
            assertEquals(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS, prefix.fieldType.indexOptions());
        }

        {
            DocumentMapper mapper = createDocumentMapper(
                fieldMapping(
                    b -> b.field("type", textFieldName)
                        .field("analyzer", "standard")
                        .startObject("index_prefixes")
                        .endObject()
                        .field("index_options", "freqs")
                )
            );
            FieldMapper prefix = (FieldMapper) mapper.mappers().getMapper("field._index_prefix");
            FieldType ft = prefix.fieldType;
            assertEquals(prefix.name(), "field._index_prefix");
            assertEquals(IndexOptions.DOCS, ft.indexOptions());
            assertFalse(ft.storeTermVectors());
        }

        {
            DocumentMapper mapper = createDocumentMapper(
                fieldMapping(
                    b -> b.field("type", textFieldName)
                        .field("analyzer", "standard")
                        .startObject("index_prefixes")
                        .endObject()
                        .field("index_options", "positions")
                )
            );
            FieldMapper prefix = (FieldMapper) mapper.mappers().getMapper("field._index_prefix");
            FieldType ft = prefix.fieldType;
            assertEquals(prefix.name(), "field._index_prefix");
            assertEquals(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS, ft.indexOptions());
            assertFalse(ft.storeTermVectors());
        }

        {
            DocumentMapper mapper = createDocumentMapper(
                fieldMapping(
                    b -> b.field("type", textFieldName)
                        .field("analyzer", "standard")
                        .startObject("index_prefixes")
                        .endObject()
                        .field("term_vector", "with_positions_offsets")
                )
            );
            FieldMapper prefix = (FieldMapper) mapper.mappers().getMapper("field._index_prefix");
            FieldType ft = prefix.fieldType;
            assertEquals(prefix.name(), "field._index_prefix");
            assertEquals(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS, ft.indexOptions());
            assertTrue(ft.storeTermVectorOffsets());
        }

        {
            DocumentMapper mapper = createDocumentMapper(
                fieldMapping(
                    b -> b.field("type", textFieldName)
                        .field("analyzer", "standard")
                        .startObject("index_prefixes")
                        .endObject()
                        .field("term_vector", "with_positions")
                )
            );
            FieldMapper prefix = (FieldMapper) mapper.mappers().getMapper("field._index_prefix");
            FieldType ft = prefix.fieldType;
            assertEquals(prefix.name(), "field._index_prefix");
            assertEquals(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS, ft.indexOptions());
            assertFalse(ft.storeTermVectorOffsets());
        }
    }

    public void testNestedIndexPrefixes() throws IOException {

    }

    public void testFastPhraseMapping() throws IOException {
        MapperService mapperService = createMapperService(mapping(b -> {
            b.startObject("field")
                .field("type", textFieldName)
                .field("analyzer", "my_stop_analyzer")
                .field("index_phrases", true)
                .endObject();
            // "standard" will be replaced with MockSynonymAnalyzer
            b.startObject("synfield").field("type", textFieldName).field("analyzer", "standard").field("index_phrases", true).endObject();
        }));
        QueryShardContext queryShardContext = createQueryShardContext(mapperService);

        Query q = new MatchPhraseQueryBuilder("field", "two words").toQuery(queryShardContext);
        assertThat(q, is(new PhraseQuery("field._index_phrase", "two words")));

        Query q2 = new MatchPhraseQueryBuilder("field", "three words here").toQuery(queryShardContext);
        assertThat(q2, is(new PhraseQuery("field._index_phrase", "three words", "words here")));

        Query q3 = new MatchPhraseQueryBuilder("field", "two words").slop(1).toQuery(queryShardContext);
        assertThat(q3, is(new PhraseQuery(1, "field", "two", "words")));

        Query q4 = new MatchPhraseQueryBuilder("field", "singleton").toQuery(queryShardContext);
        assertThat(q4, is(new TermQuery(new Term("field", "singleton"))));

        Query q5 = new MatchPhraseQueryBuilder("field", "sparkle a stopword").toQuery(queryShardContext);
        assertThat(q5, is(new PhraseQuery.Builder().add(new Term("field", "sparkle")).add(new Term("field", "stopword"), 2).build()));

        MatchQuery matchQuery = new MatchQuery(queryShardContext);
        matchQuery.setAnalyzer(new MockSynonymAnalyzer());
        Query q6 = matchQuery.parse(MatchQuery.Type.PHRASE, "synfield", "motor dogs");
        assertThat(
            q6,
            is(
                new MultiPhraseQuery.Builder().add(
                    new Term[] { new Term("synfield._index_phrase", "motor dogs"), new Term("synfield._index_phrase", "motor dog") }
                ).build()
            )
        );

        // https://github.com/elastic/elasticsearch/issues/43976
        CannedTokenStream cts = new CannedTokenStream(new Token("foo", 1, 0, 2, 2), new Token("bar", 0, 0, 2), new Token("baz", 1, 0, 2));
        Analyzer synonymAnalyzer = new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                return new TokenStreamComponents(reader -> {}, cts);
            }
        };
        matchQuery.setAnalyzer(synonymAnalyzer);
        Query q7 = matchQuery.parse(MatchQuery.Type.BOOLEAN, "synfield", "foo");
        assertThat(
            q7,
            is(
                new BooleanQuery.Builder().add(
                    new BooleanQuery.Builder().add(new TermQuery(new Term("synfield", "foo")), BooleanClause.Occur.SHOULD)
                        .add(
                            new PhraseQuery.Builder().add(new Term("synfield._index_phrase", "bar baz")).build(),
                            BooleanClause.Occur.SHOULD
                        )
                        .build(),
                    BooleanClause.Occur.SHOULD
                ).build()
            )
        );

        ParsedDocument doc = mapperService.documentMapper()
            .parse(source(b -> b.field("field", "Some English text that is going to be very useful")));

        IndexableField[] fields = doc.rootDoc().getFields("field._index_phrase");
        assertEquals(1, fields.length);

        try (TokenStream ts = fields[0].tokenStream(queryShardContext.getMapperService().indexAnalyzer(), null)) {
            CharTermAttribute termAtt = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            assertTrue(ts.incrementToken());
            assertEquals("Some English", termAtt.toString());
        }

        Exception e = expectThrows(
            MapperParsingException.class,
            () -> createMapperService(
                fieldMapping(b -> b.field("type", textFieldName).field("index", "false").field("index_phrases", true))
            )
        );
        assertThat(e.getMessage(), containsString("Cannot set index_phrases on unindexed field [field]"));

        e = expectThrows(
            MapperParsingException.class,
            () -> createMapperService(
                fieldMapping(b -> b.field("type", textFieldName).field("index_options", "freqs").field("index_phrases", true))
            )
        );
        assertThat(e.getMessage(), containsString("Cannot set index_phrases on field [field] if positions are not enabled"));
    }

    public void testIndexPrefixMapping() throws IOException {

        {
            DocumentMapper mapper = createDocumentMapper(
                fieldMapping(
                    b -> b.field("type", textFieldName)
                        .field("analyzer", "standard")
                        .startObject("index_prefixes")
                        .field("min_chars", 2)
                        .field("max_chars", 10)
                        .endObject()
                )
            );

            assertThat(mapper.mappers().getMapper("field._index_prefix").toString(), containsString("prefixChars=2:10"));

            ParsedDocument doc = mapper.parse(source(b -> b.field("field", "Some English text that is going to be very useful")));
            IndexableField[] fields = doc.rootDoc().getFields("field._index_prefix");
            assertEquals(1, fields.length);
        }

        {
            DocumentMapper mapper = createDocumentMapper(
                fieldMapping(b -> b.field("type", textFieldName).field("analyzer", "standard").startObject("index_prefixes").endObject())
            );
            assertThat(mapper.mappers().getMapper("field._index_prefix").toString(), containsString("prefixChars=2:5"));

        }

        {
            DocumentMapper mapper = createDocumentMapper(fieldMapping(b -> b.field("type", textFieldName).nullField("index_prefixes")));
            assertNull(mapper.mappers().getMapper("field._index_prefix"));
        }

        {
            MapperParsingException e = expectThrows(MapperParsingException.class, () -> createMapperService(fieldMapping(b -> {
                b.field("type", textFieldName).field("analyzer", "standard");
                b.startObject("index_prefixes").field("min_chars", 1).field("max_chars", 10).endObject();
                b.startObject("fields").startObject("_index_prefix").field("type", textFieldName).endObject().endObject();
            })));
            assertThat(e.getMessage(), containsString("Field [field._index_prefix] is defined more than once"));
        }

        {
            MapperParsingException e = expectThrows(MapperParsingException.class, () -> createMapperService(fieldMapping(b -> {
                b.field("type", textFieldName).field("analyzer", "standard");
                b.startObject("index_prefixes").field("min_chars", 11).field("max_chars", 10).endObject();
            })));
            assertThat(e.getMessage(), containsString("min_chars [11] must be less than max_chars [10]"));
        }

        {
            MapperParsingException e = expectThrows(MapperParsingException.class, () -> createMapperService(fieldMapping(b -> {
                b.field("type", textFieldName).field("analyzer", "standard");
                b.startObject("index_prefixes").field("min_chars", 0).field("max_chars", 10).endObject();
            })));
            assertThat(e.getMessage(), containsString("min_chars [0] must be greater than zero"));
        }

        {
            MapperParsingException e = expectThrows(MapperParsingException.class, () -> createMapperService(fieldMapping(b -> {
                b.field("type", textFieldName).field("analyzer", "standard");
                b.startObject("index_prefixes").field("min_chars", 1).field("max_chars", 25).endObject();
            })));
            assertThat(e.getMessage(), containsString("max_chars [25] must be less than 20"));
        }

        {
            MapperParsingException e = expectThrows(MapperParsingException.class, () -> createMapperService(fieldMapping(b -> {
                b.field("type", textFieldName).field("analyzer", "standard").field("index", false);
                b.startObject("index_prefixes").endObject();
            })));
            assertThat(e.getMessage(), containsString("Cannot set index_prefixes on unindexed field [field]"));
        }
    }

    public void testFastPhrasePrefixes() throws IOException {
        MapperService mapperService = createMapperService(mapping(b -> {
            b.startObject("field");
            {
                b.field("type", textFieldName);
                b.field("analyzer", "my_stop_analyzer");
                b.startObject("index_prefixes").field("min_chars", 2).field("max_chars", 10).endObject();
            }
            b.endObject();
            b.startObject("synfield");
            {
                b.field("type", textFieldName);
                b.field("analyzer", "standard"); // "standard" will be replaced with MockSynonymAnalyzer
                b.field("index_phrases", true);
                b.startObject("index_prefixes").field("min_chars", 2).field("max_chars", 10).endObject();
            }
            b.endObject();
        }));
        QueryShardContext queryShardContext = createQueryShardContext(mapperService);

        {
            Query q = new MatchPhrasePrefixQueryBuilder("field", "two words").toQuery(queryShardContext);
            Query expected = new SpanNearQuery.Builder("field", true).addClause(new SpanTermQuery(new Term("field", "two")))
                .addClause(new FieldMaskingSpanQuery(new SpanTermQuery(new Term("field._index_prefix", "words")), "field"))
                .build();
            assertThat(q, equalTo(expected));
        }

        {
            Query q = new MatchPhrasePrefixQueryBuilder("field", "three words here").toQuery(queryShardContext);
            Query expected = new SpanNearQuery.Builder("field", true).addClause(new SpanTermQuery(new Term("field", "three")))
                .addClause(new SpanTermQuery(new Term("field", "words")))
                .addClause(new FieldMaskingSpanQuery(new SpanTermQuery(new Term("field._index_prefix", "here")), "field"))
                .build();
            assertThat(q, equalTo(expected));
        }

        {
            Query q = new MatchPhrasePrefixQueryBuilder("field", "two words").slop(1).toQuery(queryShardContext);
            MultiPhrasePrefixQuery mpq = new MultiPhrasePrefixQuery("field");
            mpq.setSlop(1);
            mpq.add(new Term("field", "two"));
            mpq.add(new Term("field", "words"));
            assertThat(q, equalTo(mpq));
        }

        {
            Query q = new MatchPhrasePrefixQueryBuilder("field", "singleton").toQuery(queryShardContext);
            assertThat(
                q,
                is(new SynonymQuery.Builder("field._index_prefix").addTerm(new Term("field._index_prefix", "singleton")).build())
            );
        }

        {

            Query q = new MatchPhrasePrefixQueryBuilder("field", "sparkle a stopword").toQuery(queryShardContext);
            Query expected = new SpanNearQuery.Builder("field", true).addClause(new SpanTermQuery(new Term("field", "sparkle")))
                .addGap(1)
                .addClause(new FieldMaskingSpanQuery(new SpanTermQuery(new Term("field._index_prefix", "stopword")), "field"))
                .build();
            assertThat(q, equalTo(expected));
        }

        {
            MatchQuery matchQuery = new MatchQuery(queryShardContext);
            matchQuery.setAnalyzer(new MockSynonymAnalyzer());
            Query q = matchQuery.parse(MatchQuery.Type.PHRASE_PREFIX, "synfield", "motor dogs");
            Query expected = new SpanNearQuery.Builder("synfield", true).addClause(new SpanTermQuery(new Term("synfield", "motor")))
                .addClause(
                    new SpanOrQuery(
                        new FieldMaskingSpanQuery(new SpanTermQuery(new Term("synfield._index_prefix", "dogs")), "synfield"),
                        new FieldMaskingSpanQuery(new SpanTermQuery(new Term("synfield._index_prefix", "dog")), "synfield")
                    )
                )
                .build();
            assertThat(q, equalTo(expected));
        }

        {
            MatchQuery matchQuery = new MatchQuery(queryShardContext);
            matchQuery.setPhraseSlop(1);
            matchQuery.setAnalyzer(new MockSynonymAnalyzer());
            Query q = matchQuery.parse(MatchQuery.Type.PHRASE_PREFIX, "synfield", "two dogs");
            MultiPhrasePrefixQuery mpq = new MultiPhrasePrefixQuery("synfield");
            mpq.setSlop(1);
            mpq.add(new Term("synfield", "two"));
            mpq.add(new Term[] { new Term("synfield", "dogs"), new Term("synfield", "dog") });
            assertThat(q, equalTo(mpq));
        }

        {
            Query q = new MatchPhrasePrefixQueryBuilder("field", "motor d").toQuery(queryShardContext);
            MultiPhrasePrefixQuery mpq = new MultiPhrasePrefixQuery("field");
            mpq.add(new Term("field", "motor"));
            mpq.add(new Term("field", "d"));
            assertThat(q, equalTo(mpq));
        }
    }

    public void testSimpleMerge() throws IOException {
        XContentBuilder startingMapping = fieldMapping(
            b -> b.field("type", textFieldName).startObject("index_prefixes").endObject().field("index_phrases", true)
        );
        MapperService mapperService = createMapperService(startingMapping);
        assertThat(mapperService.documentMapper().mappers().getMapper("field"), instanceOf(TextFieldMapper.class));

        merge(mapperService, startingMapping);
        assertThat(mapperService.documentMapper().mappers().getMapper("field"), instanceOf(TextFieldMapper.class));

        XContentBuilder differentPrefix = fieldMapping(
            b -> b.field("type", textFieldName)
                .startObject("index_prefixes")
                .field("min_chars", "3")
                .endObject()
                .field("index_phrases", true)
        );
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> merge(mapperService, differentPrefix));
        assertThat(e.getMessage(), containsString("Cannot update parameter [index_prefixes]"));

        XContentBuilder differentPhrases = fieldMapping(
            b -> b.field("type", textFieldName).startObject("index_prefixes").endObject().field("index_phrases", false)
        );
        e = expectThrows(IllegalArgumentException.class, () -> merge(mapperService, differentPhrases));
        assertThat(e.getMessage(), containsString("Cannot update parameter [index_phrases]"));

        XContentBuilder newField = mapping(b -> {
            b.startObject("field")
                .field("type", textFieldName)
                .startObject("index_prefixes")
                .endObject()
                .field("index_phrases", true)
                .endObject();
            b.startObject("other_field").field("type", "keyword").endObject();
        });
        merge(mapperService, newField);
        assertThat(mapperService.documentMapper().mappers().getMapper("field"), instanceOf(TextFieldMapper.class));
        assertThat(mapperService.documentMapper().mappers().getMapper("other_field"), instanceOf(KeywordFieldMapper.class));
    }

    public void testPossibleToDeriveSource_WhenCopyToPresent() throws IOException {
        FieldMapper.CopyTo copyTo = new FieldMapper.CopyTo.Builder().add("copy_to_field").build();
        TextFieldMapper mapper = getMapper(copyTo, false);
        assertThrows(UnsupportedOperationException.class, mapper::canDeriveSource);
    }

    public void testPossibleToDeriveSource_WhenStoredFieldDisabled() throws IOException {
        TextFieldMapper mapper = getMapper(FieldMapper.CopyTo.empty(), false);
        assertThrows(UnsupportedOperationException.class, mapper::canDeriveSource);
    }

    public void testDerivedValueFetching_StoredField() throws IOException {
        try (Directory directory = newDirectory()) {
            TextFieldMapper mapper = getMapper(FieldMapper.CopyTo.empty(), true);
            String value = "value";
            try (IndexWriter iw = new IndexWriter(directory, new IndexWriterConfig())) {
                iw.addDocument(createDocument("field", value, false, false));
            }

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                XContentBuilder builder = XContentFactory.jsonBuilder().startObject();
                mapper.deriveSource(builder, reader.leaves().get(0).reader(), 0);
                builder.endObject();
                String source = builder.toString();
                assertEquals("{\"" + "field" + "\":" + "\"" + value + "\"" + "}", source);
            }
        }
    }

    private TextFieldMapper getMapper(FieldMapper.CopyTo copyTo, boolean isStored) throws IOException {
        MapperService mapperService = createMapperService(fieldMapping(b -> b.field("type", "text").field("store", isStored)));
        TextFieldMapper mapper = (TextFieldMapper) mapperService.documentMapper().mappers().getMapper("field");
        mapper.copyTo = copyTo;
        return mapper;
    }

    /**
     * Helper method to create a document with both doc values and stored fields
     */
    private Document createDocument(String name, String value, boolean forKeyword, boolean hasDocValues) {
        Document doc = new Document();
        final BytesRef binaryValue = new BytesRef(value);
        if (hasDocValues) {
            doc.add(new SortedSetDocValuesField(name, binaryValue));
        } else {
            if (forKeyword) {
                doc.add(new StoredField(name, binaryValue));
            } else {
                doc.add(new StoredField(name, value));
            }
        }
        return doc;
    }
}
