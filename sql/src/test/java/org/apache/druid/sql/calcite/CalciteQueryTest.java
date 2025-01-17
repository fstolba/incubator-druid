/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.sql.calcite;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import org.apache.calcite.runtime.CalciteContextException;
import org.apache.calcite.tools.ValidationException;
import org.apache.druid.common.config.NullHandling;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.JodaUtils;
import org.apache.druid.java.util.common.granularity.Granularities;
import org.apache.druid.java.util.common.granularity.PeriodGranularity;
import org.apache.druid.math.expr.ExprMacroTable;
import org.apache.druid.query.Druids;
import org.apache.druid.query.QueryDataSource;
import org.apache.druid.query.ResourceLimitExceededException;
import org.apache.druid.query.aggregation.CountAggregatorFactory;
import org.apache.druid.query.aggregation.DoubleMaxAggregatorFactory;
import org.apache.druid.query.aggregation.DoubleSumAggregatorFactory;
import org.apache.druid.query.aggregation.FilteredAggregatorFactory;
import org.apache.druid.query.aggregation.FloatMaxAggregatorFactory;
import org.apache.druid.query.aggregation.FloatMinAggregatorFactory;
import org.apache.druid.query.aggregation.LongMaxAggregatorFactory;
import org.apache.druid.query.aggregation.LongMinAggregatorFactory;
import org.apache.druid.query.aggregation.LongSumAggregatorFactory;
import org.apache.druid.query.aggregation.cardinality.CardinalityAggregatorFactory;
import org.apache.druid.query.aggregation.first.FloatFirstAggregatorFactory;
import org.apache.druid.query.aggregation.first.LongFirstAggregatorFactory;
import org.apache.druid.query.aggregation.first.StringFirstAggregatorFactory;
import org.apache.druid.query.aggregation.hyperloglog.HyperUniqueFinalizingPostAggregator;
import org.apache.druid.query.aggregation.hyperloglog.HyperUniquesAggregatorFactory;
import org.apache.druid.query.aggregation.last.FloatLastAggregatorFactory;
import org.apache.druid.query.aggregation.last.LongLastAggregatorFactory;
import org.apache.druid.query.aggregation.last.StringLastAggregatorFactory;
import org.apache.druid.query.aggregation.post.ArithmeticPostAggregator;
import org.apache.druid.query.aggregation.post.FieldAccessPostAggregator;
import org.apache.druid.query.aggregation.post.FinalizingFieldAccessPostAggregator;
import org.apache.druid.query.dimension.DefaultDimensionSpec;
import org.apache.druid.query.dimension.ExtractionDimensionSpec;
import org.apache.druid.query.extraction.RegexDimExtractionFn;
import org.apache.druid.query.extraction.SubstringDimExtractionFn;
import org.apache.druid.query.filter.BoundDimFilter;
import org.apache.druid.query.filter.DimFilter;
import org.apache.druid.query.filter.InDimFilter;
import org.apache.druid.query.filter.LikeDimFilter;
import org.apache.druid.query.filter.SelectorDimFilter;
import org.apache.druid.query.groupby.GroupByQuery;
import org.apache.druid.query.groupby.orderby.DefaultLimitSpec;
import org.apache.druid.query.groupby.orderby.OrderByColumnSpec;
import org.apache.druid.query.groupby.orderby.OrderByColumnSpec.Direction;
import org.apache.druid.query.lookup.RegisteredLookupExtractionFn;
import org.apache.druid.query.ordering.StringComparators;
import org.apache.druid.query.scan.ScanQuery;
import org.apache.druid.query.topn.DimensionTopNMetricSpec;
import org.apache.druid.query.topn.InvertedTopNMetricSpec;
import org.apache.druid.query.topn.NumericTopNMetricSpec;
import org.apache.druid.query.topn.TopNQueryBuilder;
import org.apache.druid.segment.column.ValueType;
import org.apache.druid.sql.calcite.expression.DruidExpression;
import org.apache.druid.sql.calcite.filtration.Filtration;
import org.apache.druid.sql.calcite.planner.Calcites;
import org.apache.druid.sql.calcite.rel.CannotBuildQueryException;
import org.apache.druid.sql.calcite.util.CalciteTests;
import org.hamcrest.CoreMatchers;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Interval;
import org.joda.time.Period;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.internal.matchers.ThrowableMessageMatcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class CalciteQueryTest extends BaseCalciteQueryTest
{
  @Test
  public void testSelectConstantExpression() throws Exception
  {
    // Test with a Druid-specific function, to make sure they are hooked up correctly even when not selecting
    // from a table.
    testQuery(
        "SELECT REGEXP_EXTRACT('foo', '^(.)')",
        ImmutableList.of(),
        ImmutableList.of(
            new Object[]{"f"}
        )
    );
  }

  @Test
  public void testSelectConstantExpressionFromTable() throws Exception
  {
    testQuery(
        "SELECT 1 + 1, dim1 FROM foo LIMIT 1",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE1)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .virtualColumns(expressionVirtualColumn("v0", "2", ValueType.LONG))
                .columns("dim1", "v0")
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .limit(1)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{2, ""}
        )
    );
  }


  @Test
  public void testSelectCountStart() throws Exception
  {
    testQuery(
        PLANNER_CONFIG_DEFAULT,
        QUERY_CONTEXT_DONT_SKIP_EMPTY_BUCKETS,
        "SELECT exp(count(*)) + 10, sum(m2)  FROM druid.foo WHERE  dim2 = 0",
        CalciteTests.REGULAR_USER_AUTH_RESULT,
        ImmutableList.of(Druids.newTimeseriesQueryBuilder()
                               .dataSource(CalciteTests.DATASOURCE1)
                               .intervals(querySegmentSpec(Filtration.eternity()))
                               .filters(selector("dim2", "0", null))
                               .granularity(Granularities.ALL)
                               .aggregators(aggregators(
                                   new CountAggregatorFactory("a0"),
                                   new DoubleSumAggregatorFactory("a1", "m2")
                               ))
                               .postAggregators(
                                   expressionPostAgg("p0", "(exp(\"a0\") + 10)")
                               )
                               .context(QUERY_CONTEXT_DONT_SKIP_EMPTY_BUCKETS)
                               .build()),
        ImmutableList.of(
            new Object[]{11.0, NullHandling.defaultDoubleValue()}
        )
    );

    testQuery(
        PLANNER_CONFIG_DEFAULT,
        QUERY_CONTEXT_DONT_SKIP_EMPTY_BUCKETS,
        "SELECT exp(count(*)) + 10, sum(m2)  FROM druid.foo WHERE  __time >= TIMESTAMP '2999-01-01 00:00:00'",
        CalciteTests.REGULAR_USER_AUTH_RESULT,
        ImmutableList.of(Druids.newTimeseriesQueryBuilder()
                               .dataSource(CalciteTests.DATASOURCE1)
                               .intervals(querySegmentSpec(Intervals.of(
                                   "2999-01-01T00:00:00.000Z/146140482-04-24T15:36:27.903Z"))
                               )
                               .granularity(Granularities.ALL)
                               .aggregators(aggregators(
                                   new CountAggregatorFactory("a0"),
                                   new DoubleSumAggregatorFactory("a1", "m2")
                               ))
                               .postAggregators(
                                   expressionPostAgg("p0", "(exp(\"a0\") + 10)")
                               )
                               .context(QUERY_CONTEXT_DONT_SKIP_EMPTY_BUCKETS)
                               .build()),
        ImmutableList.of(
            new Object[]{11.0, NullHandling.defaultDoubleValue()}
        )
    );

    testQuery(
        "SELECT COUNT(*) FROM foo WHERE dim1 = 'nonexistent' GROUP BY FLOOR(__time TO DAY)",
        ImmutableList.of(Druids.newTimeseriesQueryBuilder()
                               .dataSource(CalciteTests.DATASOURCE1)
                               .intervals(querySegmentSpec(Filtration.eternity()))
                               .filters(selector("dim1", "nonexistent", null))
                               .granularity(Granularities.DAY)
                               .aggregators(aggregators(
                                   new CountAggregatorFactory("a0")
                               ))
                               .context(TIMESERIES_CONTEXT_DEFAULT)
                               .build()),
        ImmutableList.of()
    );
  }

  @Test
  public void testSelectTrimFamily() throws Exception
  {
    // TRIM has some whacky parsing. Make sure the different forms work.

    testQuery(
        "SELECT\n"
        + "TRIM(BOTH 'x' FROM 'xfoox'),\n"
        + "TRIM(TRAILING 'x' FROM 'xfoox'),\n"
        + "TRIM(' ' FROM ' foo '),\n"
        + "TRIM(TRAILING FROM ' foo '),\n"
        + "TRIM(' foo '),\n"
        + "BTRIM(' foo '),\n"
        + "BTRIM('xfoox', 'x'),\n"
        + "LTRIM(' foo '),\n"
        + "LTRIM('xfoox', 'x'),\n"
        + "RTRIM(' foo '),\n"
        + "RTRIM('xfoox', 'x'),\n"
        + "COUNT(*)\n"
        + "FROM foo",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .postAggregators(
                      expressionPostAgg("p0", "'foo'"),
                      expressionPostAgg("p1", "'xfoo'"),
                      expressionPostAgg("p2", "'foo'"),
                      expressionPostAgg("p3", "' foo'"),
                      expressionPostAgg("p4", "'foo'"),
                      expressionPostAgg("p5", "'foo'"),
                      expressionPostAgg("p6", "'foo'"),
                      expressionPostAgg("p7", "'foo '"),
                      expressionPostAgg("p8", "'foox'"),
                      expressionPostAgg("p9", "' foo'"),
                      expressionPostAgg("p10", "'xfoo'")
                  )
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"foo", "xfoo", "foo", " foo", "foo", "foo", "foo", "foo ", "foox", " foo", "xfoo", 6L}
        )
    );
  }

  @Test
  public void testSelectPadFamily() throws Exception
  {
    testQuery(
        "SELECT\n"
        + "LPAD('foo', 5, 'x'),\n"
        + "LPAD('foo', 2, 'x'),\n"
        + "LPAD('foo', 5),\n"
        + "RPAD('foo', 5, 'x'),\n"
        + "RPAD('foo', 2, 'x'),\n"
        + "RPAD('foo', 5),\n"
        + "COUNT(*)\n"
        + "FROM foo",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .postAggregators(
                      expressionPostAgg("p0", "'xxfoo'"),
                      expressionPostAgg("p1", "'fo'"),
                      expressionPostAgg("p2", "'  foo'"),
                      expressionPostAgg("p3", "'fooxx'"),
                      expressionPostAgg("p4", "'fo'"),
                      expressionPostAgg("p5", "'foo  '")
                  )
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"xxfoo", "fo", "  foo", "fooxx", "fo", "foo  ", 6L}
        )
    );
  }


  @Test
  public void testExplainSelectConstantExpression() throws Exception
  {
    testQuery(
        "EXPLAIN PLAN FOR SELECT 1 + 1",
        ImmutableList.of(),
        ImmutableList.of(
            new Object[]{"BindableValues(tuples=[[{ 2 }]])\n"}
        )
    );
  }

  @Test
  public void testInformationSchemaSchemata() throws Exception
  {
    testQuery(
        "SELECT DISTINCT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA",
        ImmutableList.of(),
        ImmutableList.of(
            new Object[]{"druid"},
            new Object[]{"sys"},
            new Object[]{"INFORMATION_SCHEMA"}
        )
    );
  }

  @Test
  public void testInformationSchemaTables() throws Exception
  {
    testQuery(
        "SELECT TABLE_SCHEMA, TABLE_NAME, TABLE_TYPE\n"
        + "FROM INFORMATION_SCHEMA.TABLES\n"
        + "WHERE TABLE_TYPE IN ('SYSTEM_TABLE', 'TABLE', 'VIEW')",
        ImmutableList.of(),
        ImmutableList.<Object[]>builder()
            .add(new Object[]{"druid", CalciteTests.DATASOURCE1, "TABLE"})
            .add(new Object[]{"druid", CalciteTests.DATASOURCE2, "TABLE"})
            .add(new Object[]{"druid", CalciteTests.DATASOURCE4, "TABLE"})
            .add(new Object[]{"druid", CalciteTests.DATASOURCE3, "TABLE"})
            .add(new Object[]{"druid", "aview", "VIEW"})
            .add(new Object[]{"druid", "bview", "VIEW"})
            .add(new Object[]{"INFORMATION_SCHEMA", "COLUMNS", "SYSTEM_TABLE"})
            .add(new Object[]{"INFORMATION_SCHEMA", "SCHEMATA", "SYSTEM_TABLE"})
            .add(new Object[]{"INFORMATION_SCHEMA", "TABLES", "SYSTEM_TABLE"})
            .add(new Object[]{"sys", "segments", "SYSTEM_TABLE"})
            .add(new Object[]{"sys", "server_segments", "SYSTEM_TABLE"})
            .add(new Object[]{"sys", "servers", "SYSTEM_TABLE"})
            .add(new Object[]{"sys", "supervisors", "SYSTEM_TABLE"})
            .add(new Object[]{"sys", "tasks", "SYSTEM_TABLE"})
            .build()
    );

    testQuery(
        PLANNER_CONFIG_DEFAULT,
        "SELECT TABLE_SCHEMA, TABLE_NAME, TABLE_TYPE\n"
        + "FROM INFORMATION_SCHEMA.TABLES\n"
        + "WHERE TABLE_TYPE IN ('SYSTEM_TABLE', 'TABLE', 'VIEW')",
        CalciteTests.SUPER_USER_AUTH_RESULT,
        ImmutableList.of(),
        ImmutableList.<Object[]>builder()
            .add(new Object[]{"druid", CalciteTests.DATASOURCE1, "TABLE"})
            .add(new Object[]{"druid", CalciteTests.DATASOURCE2, "TABLE"})
            .add(new Object[]{"druid", CalciteTests.DATASOURCE4, "TABLE"})
            .add(new Object[]{"druid", CalciteTests.FORBIDDEN_DATASOURCE, "TABLE"})
            .add(new Object[]{"druid", CalciteTests.DATASOURCE3, "TABLE"})
            .add(new Object[]{"druid", "aview", "VIEW"})
            .add(new Object[]{"druid", "bview", "VIEW"})
            .add(new Object[]{"INFORMATION_SCHEMA", "COLUMNS", "SYSTEM_TABLE"})
            .add(new Object[]{"INFORMATION_SCHEMA", "SCHEMATA", "SYSTEM_TABLE"})
            .add(new Object[]{"INFORMATION_SCHEMA", "TABLES", "SYSTEM_TABLE"})
            .add(new Object[]{"sys", "segments", "SYSTEM_TABLE"})
            .add(new Object[]{"sys", "server_segments", "SYSTEM_TABLE"})
            .add(new Object[]{"sys", "servers", "SYSTEM_TABLE"})
            .add(new Object[]{"sys", "supervisors", "SYSTEM_TABLE"})
            .add(new Object[]{"sys", "tasks", "SYSTEM_TABLE"})
            .build()
    );
  }

  @Test
  public void testInformationSchemaColumnsOnTable() throws Exception
  {
    testQuery(
        "SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE\n"
        + "FROM INFORMATION_SCHEMA.COLUMNS\n"
        + "WHERE TABLE_SCHEMA = 'druid' AND TABLE_NAME = 'foo'",
        ImmutableList.of(),
        ImmutableList.of(
            new Object[]{"__time", "TIMESTAMP", "NO"},
            new Object[]{"cnt", "BIGINT", "NO"},
            new Object[]{"dim1", "VARCHAR", "YES"},
            new Object[]{"dim2", "VARCHAR", "YES"},
            new Object[]{"dim3", "VARCHAR", "YES"},
            new Object[]{"m1", "FLOAT", "NO"},
            new Object[]{"m2", "DOUBLE", "NO"},
            new Object[]{"unique_dim1", "OTHER", "YES"}
        )
    );
  }

  @Test
  public void testInformationSchemaColumnsOnForbiddenTable() throws Exception
  {
    testQuery(
        "SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE\n"
        + "FROM INFORMATION_SCHEMA.COLUMNS\n"
        + "WHERE TABLE_SCHEMA = 'druid' AND TABLE_NAME = 'forbiddenDatasource'",
        ImmutableList.of(),
        ImmutableList.of()
    );

    testQuery(
        PLANNER_CONFIG_DEFAULT,
        "SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE\n"
        + "FROM INFORMATION_SCHEMA.COLUMNS\n"
        + "WHERE TABLE_SCHEMA = 'druid' AND TABLE_NAME = 'forbiddenDatasource'",
        CalciteTests.SUPER_USER_AUTH_RESULT,
        ImmutableList.of(),
        ImmutableList.of(
            new Object[]{"__time", "TIMESTAMP", "NO"},
            new Object[]{"cnt", "BIGINT", "NO"},
            new Object[]{"dim1", "VARCHAR", "YES"},
            new Object[]{"dim2", "VARCHAR", "YES"},
            new Object[]{"m1", "FLOAT", "NO"},
            new Object[]{"m2", "DOUBLE", "NO"},
            new Object[]{"unique_dim1", "OTHER", "YES"}
        )
    );
  }

  @Test
  public void testInformationSchemaColumnsOnView() throws Exception
  {
    testQuery(
        "SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE\n"
        + "FROM INFORMATION_SCHEMA.COLUMNS\n"
        + "WHERE TABLE_SCHEMA = 'druid' AND TABLE_NAME = 'aview'",
        ImmutableList.of(),
        ImmutableList.of(
            new Object[]{"dim1_firstchar", "VARCHAR", "YES"}
        )
    );
  }

  @Test
  public void testExplainInformationSchemaColumns() throws Exception
  {
    final String explanation =
        "BindableProject(COLUMN_NAME=[$3], DATA_TYPE=[$7])\n"
        + "  BindableFilter(condition=[AND(=($1, 'druid'), =($2, 'foo'))])\n"
        + "    BindableTableScan(table=[[INFORMATION_SCHEMA, COLUMNS]])\n";

    testQuery(
        "EXPLAIN PLAN FOR\n"
        + "SELECT COLUMN_NAME, DATA_TYPE\n"
        + "FROM INFORMATION_SCHEMA.COLUMNS\n"
        + "WHERE TABLE_SCHEMA = 'druid' AND TABLE_NAME = 'foo'",
        ImmutableList.of(),
        ImmutableList.of(
            new Object[]{explanation}
        )
    );
  }

  @Test
  public void testAggregatorsOnInformationSchemaColumns() throws Exception
  {
    // Not including COUNT DISTINCT, since it isn't supported by BindableAggregate, and so it can't work.
    testQuery(
        "SELECT\n"
        + "  COUNT(JDBC_TYPE),\n"
        + "  SUM(JDBC_TYPE),\n"
        + "  AVG(JDBC_TYPE),\n"
        + "  MIN(JDBC_TYPE),\n"
        + "  MAX(JDBC_TYPE)\n"
        + "FROM INFORMATION_SCHEMA.COLUMNS\n"
        + "WHERE TABLE_SCHEMA = 'druid' AND TABLE_NAME = 'foo'",
        ImmutableList.of(),
        ImmutableList.of(
            new Object[]{8L, 1249L, 156L, -5L, 1111L}
        )
    );
  }

  @Test
  public void testSelectStar() throws Exception
  {
    String hyperLogLogCollectorClassName = HLLC_STRING;
    testQuery(
        PLANNER_CONFIG_DEFAULT_NO_COMPLEX_SERDE,
        QUERY_CONTEXT_DEFAULT,
        "SELECT * FROM druid.foo",
        CalciteTests.REGULAR_USER_AUTH_RESULT,
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE1)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .columns("__time", "cnt", "dim1", "dim2", "dim3", "m1", "m2", "unique_dim1")
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{timestamp("2000-01-01"), 1L, "", "a", "[\"a\",\"b\"]", 1f, 1.0, hyperLogLogCollectorClassName},
            new Object[]{
                timestamp("2000-01-02"), 1L, "10.1", NULL_VALUE, "[\"b\",\"c\"]", 2f, 2.0, hyperLogLogCollectorClassName
            },
            new Object[]{timestamp("2000-01-03"), 1L, "2", "", "d", 3f, 3.0, hyperLogLogCollectorClassName},
            new Object[]{timestamp("2001-01-01"), 1L, "1", "a", "", 4f, 4.0, hyperLogLogCollectorClassName},
            new Object[]{timestamp("2001-01-02"), 1L, "def", "abc", NULL_VALUE, 5f, 5.0, hyperLogLogCollectorClassName},
            new Object[]{
                timestamp("2001-01-03"),
                1L,
                "abc",
                NULL_VALUE,
                NULL_VALUE,
                6f,
                6.0,
                hyperLogLogCollectorClassName
            }
        )
    );
  }

  @Test
  public void testSelectStarOnForbiddenTable() throws Exception
  {
    assertQueryIsForbidden(
        "SELECT * FROM druid.forbiddenDatasource",
        CalciteTests.REGULAR_USER_AUTH_RESULT
    );

    testQuery(
        PLANNER_CONFIG_DEFAULT,
        "SELECT * FROM druid.forbiddenDatasource",
        CalciteTests.SUPER_USER_AUTH_RESULT,
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.FORBIDDEN_DATASOURCE)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .columns("__time", "cnt", "dim1", "dim2", "m1", "m2", "unique_dim1")
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{
                timestamp("2000-01-01"),
                1L,
                "forbidden",
                "abcd",
                9999.0f,
                NullHandling.defaultDoubleValue(),
                "\"AQAAAQAAAALFBA==\""
            }
        )
    );
  }

  @Test
  public void testUnqualifiedTableName() throws Exception
  {
    testQuery(
        "SELECT COUNT(*) FROM foo",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{6L}
        )
    );
  }

  @Test
  public void testExplainSelectStar() throws Exception
  {
    // Skip vectorization since otherwise the "context" will change for each subtest.
    skipVectorize();

    testQuery(
        "EXPLAIN PLAN FOR SELECT * FROM druid.foo",
        ImmutableList.of(),
        ImmutableList.of(
            new Object[]{
                "DruidQueryRel(query=[{\"queryType\":\"scan\",\"dataSource\":{\"type\":\"table\",\"name\":\"foo\"},\"intervals\":{\"type\":\"intervals\",\"intervals\":[\"-146136543-09-08T08:23:32.096Z/146140482-04-24T15:36:27.903Z\"]},\"virtualColumns\":[],\"resultFormat\":\"compactedList\",\"batchSize\":20480,\"limit\":9223372036854775807,\"order\":\"none\",\"filter\":null,\"columns\":[\"__time\",\"cnt\",\"dim1\",\"dim2\",\"dim3\",\"m1\",\"m2\",\"unique_dim1\"],\"legacy\":false,\"context\":{\"defaultTimeout\":300000,\"maxScatterGatherBytes\":9223372036854775807,\"sqlCurrentTimestamp\":\"2000-01-01T00:00:00Z\",\"sqlQueryId\":\"dummy\",\"vectorize\":\"false\"},\"descending\":false,\"granularity\":{\"type\":\"all\"}}], signature=[{__time:LONG, cnt:LONG, dim1:STRING, dim2:STRING, dim3:STRING, m1:FLOAT, m2:DOUBLE, unique_dim1:COMPLEX}])\n"
            }
        )
    );
  }

  @Test
  public void testSelectStarWithLimit() throws Exception
  {
    testQuery(
        PLANNER_CONFIG_DEFAULT_NO_COMPLEX_SERDE,
        QUERY_CONTEXT_DEFAULT,
        "SELECT * FROM druid.foo LIMIT 2",
        CalciteTests.REGULAR_USER_AUTH_RESULT,
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE1)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .columns("__time", "cnt", "dim1", "dim2", "dim3", "m1", "m2", "unique_dim1")
                .limit(2)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{timestamp("2000-01-01"), 1L, "", "a", "[\"a\",\"b\"]", 1.0f, 1.0, HLLC_STRING},
            new Object[]{timestamp("2000-01-02"), 1L, "10.1", NULL_VALUE, "[\"b\",\"c\"]", 2.0f, 2.0, HLLC_STRING}
        )
    );
  }

  @Test
  public void testSelectWithProjection() throws Exception
  {
    testQuery(
        "SELECT SUBSTRING(dim2, 1, 1) FROM druid.foo LIMIT 2",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE1)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .virtualColumns(
                    expressionVirtualColumn("v0", "substring(\"dim2\", 0, 1)", ValueType.STRING)
                )
                .columns("v0")
                .limit(2)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"a"},
            new Object[]{NULL_VALUE}
        )
    );
  }

  @Test
  public void testSelectWithExpressionFilter() throws Exception
  {
    testQuery(
        "SELECT dim1 FROM druid.foo WHERE m1 + 1 = 7",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE1)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .virtualColumns(
                    expressionVirtualColumn("v0", "(\"m1\" + 1)", ValueType.FLOAT)
                )
                .filters(selector("v0", "7", null))
                .columns("dim1")
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"abc"}
        )
    );
  }

  @Test
  public void testSelectStarWithLimitTimeDescending() throws Exception
  {
    testQuery(
        PLANNER_CONFIG_DEFAULT_NO_COMPLEX_SERDE,
        QUERY_CONTEXT_DEFAULT,
        "SELECT * FROM druid.foo ORDER BY __time DESC LIMIT 2",
        CalciteTests.REGULAR_USER_AUTH_RESULT,
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE1)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .columns(ImmutableList.of("__time", "cnt", "dim1", "dim2", "dim3", "m1", "m2", "unique_dim1"))
                .limit(2)
                .order(ScanQuery.Order.DESCENDING)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{timestamp("2001-01-03"), 1L, "abc", NULL_VALUE, NULL_VALUE, 6f, 6d, HLLC_STRING},
            new Object[]{timestamp("2001-01-02"), 1L, "def", "abc", NULL_VALUE, 5f, 5d, HLLC_STRING}
        )
    );
  }

  @Test
  public void testSelectStarWithoutLimitTimeAscending() throws Exception
  {
    testQuery(
        PLANNER_CONFIG_DEFAULT_NO_COMPLEX_SERDE,
        QUERY_CONTEXT_DEFAULT,
        "SELECT * FROM druid.foo ORDER BY __time",
        CalciteTests.REGULAR_USER_AUTH_RESULT,
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE1)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .columns(ImmutableList.of("__time", "cnt", "dim1", "dim2", "dim3", "m1", "m2", "unique_dim1"))
                .limit(Long.MAX_VALUE)
                .order(ScanQuery.Order.ASCENDING)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{timestamp("2000-01-01"), 1L, "", "a", "[\"a\",\"b\"]", 1f, 1.0, HLLC_STRING},
            new Object[]{timestamp("2000-01-02"), 1L, "10.1", NULL_VALUE, "[\"b\",\"c\"]", 2f, 2.0, HLLC_STRING},
            new Object[]{timestamp("2000-01-03"), 1L, "2", "", "d", 3f, 3.0, HLLC_STRING},
            new Object[]{timestamp("2001-01-01"), 1L, "1", "a", "", 4f, 4.0, HLLC_STRING},
            new Object[]{timestamp("2001-01-02"), 1L, "def", "abc", NULL_VALUE, 5f, 5.0, HLLC_STRING},
            new Object[]{timestamp("2001-01-03"), 1L, "abc", NULL_VALUE, NULL_VALUE, 6f, 6.0, HLLC_STRING}
        )
    );
  }

  @Test
  public void testSelectSingleColumnTwice() throws Exception
  {
    testQuery(
        "SELECT dim2 x, dim2 y FROM druid.foo LIMIT 2",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE1)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .columns("dim2")
                .limit(2)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"a", "a"},
            new Object[]{NULL_VALUE, NULL_VALUE}
        )
    );
  }

  @Test
  public void testSelectSingleColumnWithLimitDescending() throws Exception
  {
    testQuery(
        "SELECT dim1 FROM druid.foo ORDER BY __time DESC LIMIT 2",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE1)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .columns(ImmutableList.of("__time", "dim1"))
                .limit(2)
                .order(ScanQuery.Order.DESCENDING)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"abc"},
            new Object[]{"def"}
        )
    );
  }

  @Test
  public void testSelectStarFromSelectSingleColumnWithLimitDescending() throws Exception
  {
    testQuery(
        "SELECT * FROM (SELECT dim1 FROM druid.foo ORDER BY __time DESC) LIMIT 2",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE1)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .columns(ImmutableList.of("__time", "dim1"))
                .limit(2)
                .order(ScanQuery.Order.DESCENDING)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"abc"},
            new Object[]{"def"}
        )
    );
  }

  @Test
  public void testSelectProjectionFromSelectSingleColumnWithInnerLimitDescending() throws Exception
  {
    testQuery(
        "SELECT 'beep ' || dim1 FROM (SELECT dim1 FROM druid.foo ORDER BY __time DESC LIMIT 2)",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE1)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .virtualColumns(expressionVirtualColumn("v0", "concat('beep ',\"dim1\")", ValueType.STRING))
                .columns(ImmutableList.of("__time", "v0"))
                .limit(2)
                .order(ScanQuery.Order.DESCENDING)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"beep abc"},
            new Object[]{"beep def"}
        )
    );
  }

  @Test
  public void testSelectProjectionFromSelectSingleColumnDescending() throws Exception
  {
    // Regression test for https://github.com/apache/incubator-druid/issues/7768.

    testQuery(
        "SELECT 'beep ' || dim1 FROM (SELECT dim1 FROM druid.foo ORDER BY __time DESC)",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE1)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .virtualColumns(expressionVirtualColumn("v0", "concat('beep ',\"dim1\")", ValueType.STRING))
                .columns(ImmutableList.of("__time", "v0"))
                .order(ScanQuery.Order.DESCENDING)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"beep abc"},
            new Object[]{"beep def"},
            new Object[]{"beep 1"},
            new Object[]{"beep 2"},
            new Object[]{"beep 10.1"},
            new Object[]{"beep "}
        )
    );
  }

  @Test
  public void testSelectProjectionFromSelectSingleColumnWithInnerAndOuterLimitDescending() throws Exception
  {
    testQuery(
        "SELECT 'beep ' || dim1 FROM (SELECT dim1 FROM druid.foo ORDER BY __time DESC LIMIT 4) LIMIT 2",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE1)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .virtualColumns(expressionVirtualColumn("v0", "concat('beep ',\"dim1\")", ValueType.STRING))
                .columns(ImmutableList.of("__time", "v0"))
                .limit(2)
                .order(ScanQuery.Order.DESCENDING)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"beep abc"},
            new Object[]{"beep def"}
        )
    );
  }

  @Test
  public void testGroupBySingleColumnDescendingNoTopN() throws Exception
  {
    testQuery(
        PLANNER_CONFIG_DEFAULT,
        "SELECT dim1 FROM druid.foo GROUP BY dim1 ORDER BY dim1 DESC",
        CalciteTests.REGULAR_USER_AUTH_RESULT,
        ImmutableList.of(
            new GroupByQuery.Builder()
                .setDataSource(CalciteTests.DATASOURCE1)
                .setInterval(querySegmentSpec(Filtration.eternity()))
                .setDimensions(dimensions(new DefaultDimensionSpec("dim1", "d0")))
                .setGranularity(Granularities.ALL)
                .setLimitSpec(
                    new DefaultLimitSpec(
                        ImmutableList.of(
                            new OrderByColumnSpec(
                                "d0",
                                OrderByColumnSpec.Direction.DESCENDING,
                                StringComparators.LEXICOGRAPHIC
                            )
                        ),
                        Integer.MAX_VALUE
                    )
                )
                .setContext(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"def"},
            new Object[]{"abc"},
            new Object[]{"2"},
            new Object[]{"10.1"},
            new Object[]{"1"},
            new Object[]{""}
        )
    );
  }

  @Test
  public void testEarliestAggregators() throws Exception
  {
    // Cannot vectorize EARLIEST aggregator.
    skipVectorize();

    testQuery(
        "SELECT "
        + "EARLIEST(cnt), EARLIEST(m1), EARLIEST(dim1, 10), "
        + "EARLIEST(cnt + 1), EARLIEST(m1 + 1), EARLIEST(dim1 || CAST(cnt AS VARCHAR), 10) "
        + "FROM druid.foo",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .virtualColumns(
                      expressionVirtualColumn("v0", "(\"cnt\" + 1)", ValueType.LONG),
                      expressionVirtualColumn("v1", "(\"m1\" + 1)", ValueType.FLOAT),
                      expressionVirtualColumn("v2", "concat(\"dim1\",CAST(\"cnt\", 'STRING'))", ValueType.STRING)
                  )
                  .aggregators(
                      aggregators(
                          new LongFirstAggregatorFactory("a0", "cnt"),
                          new FloatFirstAggregatorFactory("a1", "m1"),
                          new StringFirstAggregatorFactory("a2", "dim1", 10),
                          new LongFirstAggregatorFactory("a3", "v0"),
                          new FloatFirstAggregatorFactory("a4", "v1"),
                          new StringFirstAggregatorFactory("a5", "v2", 10)
                      )
                  )
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{1L, 1.0f, NullHandling.sqlCompatible() ? "" : "10.1", 2L, 2.0f, "1"}
        )
    );
  }

  @Test
  public void testLatestAggregators() throws Exception
  {
    // Cannot vectorize LATEST aggregator.
    skipVectorize();

    testQuery(
        "SELECT "
        + "LATEST(cnt), LATEST(m1), LATEST(dim1, 10), "
        + "LATEST(cnt + 1), LATEST(m1 + 1), LATEST(dim1 || CAST(cnt AS VARCHAR), 10) "
        + "FROM druid.foo",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .virtualColumns(
                      expressionVirtualColumn("v0", "(\"cnt\" + 1)", ValueType.LONG),
                      expressionVirtualColumn("v1", "(\"m1\" + 1)", ValueType.FLOAT),
                      expressionVirtualColumn("v2", "concat(\"dim1\",CAST(\"cnt\", 'STRING'))", ValueType.STRING)
                  )
                  .aggregators(
                      aggregators(
                          new LongLastAggregatorFactory("a0", "cnt"),
                          new FloatLastAggregatorFactory("a1", "m1"),
                          new StringLastAggregatorFactory("a2", "dim1", 10),
                          new LongLastAggregatorFactory("a3", "v0"),
                          new FloatLastAggregatorFactory("a4", "v1"),
                          new StringLastAggregatorFactory("a5", "v2", 10)
                      )
                  )
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{1L, 6.0f, "abc", 2L, 7.0f, "abc1"}
        )
    );
  }

  @Test
  public void testLatestInSubquery() throws Exception
  {
    // Cannot vectorize LATEST aggregator.
    skipVectorize();

    testQuery(
        "SELECT SUM(val) FROM (SELECT dim2, LATEST(m1) AS val FROM foo GROUP BY dim2)",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(
                            GroupByQuery.builder()
                                        .setDataSource(CalciteTests.DATASOURCE1)
                                        .setInterval(querySegmentSpec(Filtration.eternity()))
                                        .setGranularity(Granularities.ALL)
                                        .setDimensions(dimensions(new DefaultDimensionSpec("dim2", "d0")))
                                        .setAggregatorSpecs(aggregators(new FloatLastAggregatorFactory("a0:a", "m1")))
                                        .setPostAggregatorSpecs(
                                            ImmutableList.of(
                                                new FinalizingFieldAccessPostAggregator("a0", "a0:a")
                                            )
                                        )
                                        .setContext(QUERY_CONTEXT_DEFAULT)
                                        .build()
                        )
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setAggregatorSpecs(aggregators(new DoubleSumAggregatorFactory("_a0", "a0")))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{NullHandling.sqlCompatible() ? 18.0 : 15.0}
        )
    );
  }

  @Test
  public void testGroupByLong() throws Exception
  {
    testQuery(
        "SELECT cnt, COUNT(*) FROM druid.foo GROUP BY cnt",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimensions(dimensions(new DefaultDimensionSpec("cnt", "d0", ValueType.LONG)))
                        .setAggregatorSpecs(aggregators(new CountAggregatorFactory("a0")))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{1L, 6L}
        )
    );
  }

  @Test
  public void testGroupByOrdinal() throws Exception
  {
    testQuery(
        "SELECT cnt, COUNT(*) FROM druid.foo GROUP BY 1",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimensions(dimensions(new DefaultDimensionSpec("cnt", "d0", ValueType.LONG)))
                        .setAggregatorSpecs(aggregators(new CountAggregatorFactory("a0")))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{1L, 6L}
        )
    );
  }

  @Test
  @Ignore // Disabled since GROUP BY alias can confuse the validator; see DruidConformance::isGroupByAlias
  public void testGroupByAndOrderByAlias() throws Exception
  {
    testQuery(
        "SELECT cnt AS theCnt, COUNT(*) FROM druid.foo GROUP BY theCnt ORDER BY theCnt ASC",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimensions(dimensions(new DefaultDimensionSpec("cnt", "d0", ValueType.LONG)))
                        .setAggregatorSpecs(aggregators(new CountAggregatorFactory("a0")))
                        .setLimitSpec(
                            new DefaultLimitSpec(
                                ImmutableList.of(
                                    new OrderByColumnSpec(
                                        "d0",
                                        OrderByColumnSpec.Direction.ASCENDING,
                                        StringComparators.NUMERIC
                                    )
                                ),
                                Integer.MAX_VALUE
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{1L, 6L}
        )
    );
  }

  @Test
  public void testGroupByExpressionAliasedAsOriginalColumnName() throws Exception
  {
    testQuery(
        "SELECT\n"
        + "FLOOR(__time TO MONTH) AS __time,\n"
        + "COUNT(*)\n"
        + "FROM druid.foo\n"
        + "GROUP BY FLOOR(__time TO MONTH)",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.MONTH)
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{timestamp("2000-01-01"), 3L},
            new Object[]{timestamp("2001-01-01"), 3L}
        )
    );
  }

  @Test
  public void testGroupByAndOrderByOrdinalOfAlias() throws Exception
  {
    testQuery(
        "SELECT cnt as theCnt, COUNT(*) FROM druid.foo GROUP BY 1 ORDER BY 1 ASC",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimensions(dimensions(new DefaultDimensionSpec("cnt", "d0", ValueType.LONG)))
                        .setAggregatorSpecs(aggregators(new CountAggregatorFactory("a0")))
                        .setLimitSpec(
                            new DefaultLimitSpec(
                                ImmutableList.of(
                                    new OrderByColumnSpec(
                                        "d0",
                                        OrderByColumnSpec.Direction.ASCENDING,
                                        StringComparators.NUMERIC
                                    )
                                ),
                                Integer.MAX_VALUE
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{1L, 6L}
        )
    );
  }

  @Test
  public void testGroupByFloat() throws Exception
  {
    testQuery(
        "SELECT m1, COUNT(*) FROM druid.foo GROUP BY m1",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimensions(dimensions(new DefaultDimensionSpec("m1", "d0", ValueType.FLOAT)))
                        .setAggregatorSpecs(aggregators(new CountAggregatorFactory("a0")))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{1.0f, 1L},
            new Object[]{2.0f, 1L},
            new Object[]{3.0f, 1L},
            new Object[]{4.0f, 1L},
            new Object[]{5.0f, 1L},
            new Object[]{6.0f, 1L}
        )
    );
  }

  @Test
  public void testGroupByDouble() throws Exception
  {
    testQuery(
        "SELECT m2, COUNT(*) FROM druid.foo GROUP BY m2",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimensions(dimensions(new DefaultDimensionSpec("m2", "d0", ValueType.DOUBLE)))
                        .setAggregatorSpecs(aggregators(new CountAggregatorFactory("a0")))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{1.0d, 1L},
            new Object[]{2.0d, 1L},
            new Object[]{3.0d, 1L},
            new Object[]{4.0d, 1L},
            new Object[]{5.0d, 1L},
            new Object[]{6.0d, 1L}
        )
    );
  }

  @Test
  public void testFilterOnFloat() throws Exception
  {
    testQuery(
        "SELECT COUNT(*) FROM druid.foo WHERE m1 = 1.0",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .filters(selector("m1", "1.0", null))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{1L}
        )
    );
  }

  @Test
  public void testFilterOnDouble() throws Exception
  {
    testQuery(
        "SELECT COUNT(*) FROM druid.foo WHERE m2 = 1.0",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .filters(selector("m2", "1.0", null))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{1L}
        )
    );
  }

  @Test
  public void testHavingOnGrandTotal() throws Exception
  {
    testQuery(
        "SELECT SUM(m1) AS m1_sum FROM foo HAVING m1_sum = 21",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setAggregatorSpecs(aggregators(new DoubleSumAggregatorFactory("a0", "m1")))
                        .setHavingSpec(having(selector("a0", "21", null)))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{21d}
        )
    );
  }

  @Test
  public void testHavingOnDoubleSum() throws Exception
  {
    testQuery(
        "SELECT dim1, SUM(m1) AS m1_sum FROM druid.foo GROUP BY dim1 HAVING SUM(m1) > 1",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimensions(dimensions(new DefaultDimensionSpec("dim1", "d0")))
                        .setAggregatorSpecs(aggregators(new DoubleSumAggregatorFactory("a0", "m1")))
                        .setHavingSpec(
                            having(
                                new BoundDimFilter(
                                    "a0",
                                    "1",
                                    null,
                                    true,
                                    false,
                                    false,
                                    null,
                                    StringComparators.NUMERIC
                                )
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{"1", 4.0d},
            new Object[]{"10.1", 2.0d},
            new Object[]{"2", 3.0d},
            new Object[]{"abc", 6.0d},
            new Object[]{"def", 5.0d}
        )
    );
  }

  @Test
  public void testHavingOnApproximateCountDistinct() throws Exception
  {
    // Cannot vectorize due to "cardinality" aggregator.
    cannotVectorize();

    testQuery(
        "SELECT dim2, COUNT(DISTINCT m1) FROM druid.foo GROUP BY dim2 HAVING COUNT(DISTINCT m1) > 1",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimensions(dimensions(new DefaultDimensionSpec("dim2", "d0")))
                        .setAggregatorSpecs(
                            aggregators(
                                new CardinalityAggregatorFactory(
                                    "a0",
                                    null,
                                    ImmutableList.of(
                                        new DefaultDimensionSpec("m1", "m1", ValueType.FLOAT)
                                    ),
                                    false,
                                    true
                                )
                            )
                        )
                        .setHavingSpec(
                            having(
                                bound(
                                    "a0",
                                    "1",
                                    null,
                                    true,
                                    false,
                                    null,
                                    StringComparators.NUMERIC
                                )
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        NullHandling.replaceWithDefault() ?
        ImmutableList.of(
            new Object[]{"", 3L},
            new Object[]{"a", 2L}
        ) :
        ImmutableList.of(
            new Object[]{null, 2L},
            new Object[]{"a", 2L}
        )
    );
  }

  @Test
  public void testHavingOnExactCountDistinct() throws Exception
  {
    testQuery(
        PLANNER_CONFIG_NO_HLL,
        "SELECT dim2, COUNT(DISTINCT m1) FROM druid.foo GROUP BY dim2 HAVING COUNT(DISTINCT m1) > 1",
        CalciteTests.REGULAR_USER_AUTH_RESULT,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(
                            new QueryDataSource(
                                GroupByQuery.builder()
                                            .setDataSource(CalciteTests.DATASOURCE1)
                                            .setInterval(querySegmentSpec(Filtration.eternity()))
                                            .setGranularity(Granularities.ALL)
                                            .setDimensions(
                                                dimensions(
                                                    new DefaultDimensionSpec("dim2", "d0", ValueType.STRING),
                                                    new DefaultDimensionSpec("m1", "d1", ValueType.FLOAT)
                                                )
                                            )
                                            .setContext(QUERY_CONTEXT_DEFAULT)
                                            .build()
                            )
                        )
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimensions(dimensions(new DefaultDimensionSpec("d0", "_d0", ValueType.STRING)))
                        .setAggregatorSpecs(aggregators(new CountAggregatorFactory("a0")))
                        .setHavingSpec(
                            having(
                                bound(
                                    "a0",
                                    "1",
                                    null,
                                    true,
                                    false,
                                    null,
                                    StringComparators.NUMERIC
                                )
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        NullHandling.replaceWithDefault() ?
        ImmutableList.of(
            new Object[]{"", 3L},
            new Object[]{"a", 2L}
        ) :
        ImmutableList.of(
            new Object[]{null, 2L},
            new Object[]{"a", 2L}
        )
    );
  }

  @Test
  public void testHavingOnFloatSum() throws Exception
  {
    testQuery(
        "SELECT dim1, CAST(SUM(m1) AS FLOAT) AS m1_sum FROM druid.foo GROUP BY dim1 HAVING CAST(SUM(m1) AS FLOAT) > 1",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimensions(dimensions(new DefaultDimensionSpec("dim1", "d0")))
                        .setAggregatorSpecs(aggregators(new DoubleSumAggregatorFactory("a0", "m1")))
                        .setHavingSpec(
                            having(
                                new BoundDimFilter(
                                    "a0",
                                    "1",
                                    null,
                                    true,
                                    false,
                                    false,
                                    null,
                                    StringComparators.NUMERIC
                                )
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{"1", 4.0f},
            new Object[]{"10.1", 2.0f},
            new Object[]{"2", 3.0f},
            new Object[]{"abc", 6.0f},
            new Object[]{"def", 5.0f}
        )
    );
  }

  @Test
  public void testColumnComparison() throws Exception
  {
    // Cannot vectorize due to expression filter.
    cannotVectorize();

    testQuery(
        "SELECT dim1, m1, COUNT(*) FROM druid.foo WHERE m1 - 1 = dim1 GROUP BY dim1, m1",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimFilter(expressionFilter("((\"m1\" - 1) == \"dim1\")"))
                        .setDimensions(dimensions(
                            new DefaultDimensionSpec("dim1", "d0"),
                            new DefaultDimensionSpec("m1", "d1", ValueType.FLOAT)
                        ))
                        .setAggregatorSpecs(aggregators(new CountAggregatorFactory("a0")))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        NullHandling.replaceWithDefault() ?
        ImmutableList.of(
            new Object[]{"", 1.0f, 1L},
            new Object[]{"2", 3.0f, 1L}
        ) :
        ImmutableList.of(
            new Object[]{"2", 3.0f, 1L}
        )
    );
  }

  @Test
  public void testHavingOnRatio() throws Exception
  {
    // Test for https://github.com/apache/incubator-druid/issues/4264

    testQuery(
        "SELECT\n"
        + "  dim1,\n"
        + "  COUNT(*) FILTER(WHERE dim2 <> 'a')/COUNT(*) as ratio\n"
        + "FROM druid.foo\n"
        + "GROUP BY dim1\n"
        + "HAVING COUNT(*) FILTER(WHERE dim2 <> 'a')/COUNT(*) = 1",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimensions(dimensions(new DefaultDimensionSpec("dim1", "d0")))
                        .setAggregatorSpecs(aggregators(
                            new FilteredAggregatorFactory(
                                new CountAggregatorFactory("a0"),
                                not(selector("dim2", "a", null))
                            ),
                            new CountAggregatorFactory("a1")
                        ))
                        .setPostAggregatorSpecs(ImmutableList.of(
                            expressionPostAgg("p0", "(\"a0\" / \"a1\")")
                        ))
                        .setHavingSpec(having(expressionFilter("((\"a0\" / \"a1\") == 1)")))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{"10.1", 1L},
            new Object[]{"2", 1L},
            new Object[]{"abc", 1L},
            new Object[]{"def", 1L}
        )
    );
  }

  @Test
  public void testGroupByWithSelectProjections() throws Exception
  {
    testQuery(
        "SELECT\n"
        + "  dim1,"
        + "  SUBSTRING(dim1, 2)\n"
        + "FROM druid.foo\n"
        + "GROUP BY dim1\n",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimensions(dimensions(new DefaultDimensionSpec("dim1", "d0")))
                        .setPostAggregatorSpecs(ImmutableList.of(
                            expressionPostAgg("p0", "substring(\"d0\", 1, -1)")
                        ))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{"", NULL_VALUE},
            new Object[]{"1", NULL_VALUE},
            new Object[]{"10.1", "0.1"},
            new Object[]{"2", NULL_VALUE},
            new Object[]{"abc", "bc"},
            new Object[]{"def", "ef"}
        )
    );
  }

  @Test
  public void testGroupByWithSelectAndOrderByProjections() throws Exception
  {
    testQuery(
        "SELECT\n"
        + "  dim1,"
        + "  SUBSTRING(dim1, 2)\n"
        + "FROM druid.foo\n"
        + "GROUP BY dim1\n"
        + "ORDER BY CHARACTER_LENGTH(dim1) DESC, dim1",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimensions(dimensions(new DefaultDimensionSpec("dim1", "d0")))
                        .setPostAggregatorSpecs(ImmutableList.of(
                            expressionPostAgg("p0", "substring(\"d0\", 1, -1)"),
                            expressionPostAgg("p1", "strlen(\"d0\")")
                        ))
                        .setLimitSpec(new DefaultLimitSpec(
                            ImmutableList.of(
                                new OrderByColumnSpec(
                                    "p1",
                                    OrderByColumnSpec.Direction.DESCENDING,
                                    StringComparators.NUMERIC
                                ),
                                new OrderByColumnSpec(
                                    "d0",
                                    OrderByColumnSpec.Direction.ASCENDING,
                                    StringComparators.LEXICOGRAPHIC
                                )
                            ),
                            Integer.MAX_VALUE
                        ))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{"10.1", "0.1"},
            new Object[]{"abc", "bc"},
            new Object[]{"def", "ef"},
            new Object[]{"1", NULL_VALUE},
            new Object[]{"2", NULL_VALUE},
            new Object[]{"", NULL_VALUE}
        )
    );
  }

  @Test
  public void testTopNWithSelectProjections() throws Exception
  {
    testQuery(
        "SELECT\n"
        + "  dim1,"
        + "  SUBSTRING(dim1, 2)\n"
        + "FROM druid.foo\n"
        + "GROUP BY dim1\n"
        + "LIMIT 10",
        ImmutableList.of(
            new TopNQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE1)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .granularity(Granularities.ALL)
                .dimension(new DefaultDimensionSpec("dim1", "d0"))
                .postAggregators(expressionPostAgg("p0", "substring(\"d0\", 1, -1)"))
                .metric(new DimensionTopNMetricSpec(null, StringComparators.LEXICOGRAPHIC))
                .threshold(10)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"", NULL_VALUE},
            new Object[]{"1", NULL_VALUE},
            new Object[]{"10.1", "0.1"},
            new Object[]{"2", NULL_VALUE},
            new Object[]{"abc", "bc"},
            new Object[]{"def", "ef"}
        )
    );
  }

  @Test
  public void testTopNWithSelectAndOrderByProjections() throws Exception
  {

    testQuery(
        "SELECT\n"
        + "  dim1,"
        + "  SUBSTRING(dim1, 2)\n"
        + "FROM druid.foo\n"
        + "GROUP BY dim1\n"
        + "ORDER BY CHARACTER_LENGTH(dim1) DESC\n"
        + "LIMIT 10",
        ImmutableList.of(
            new TopNQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE1)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .granularity(Granularities.ALL)
                .dimension(new DefaultDimensionSpec("dim1", "d0"))
                .postAggregators(
                    expressionPostAgg("p0", "substring(\"d0\", 1, -1)"),
                    expressionPostAgg("p1", "strlen(\"d0\")")
                )
                .metric(new NumericTopNMetricSpec("p1"))
                .threshold(10)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"10.1", "0.1"},
            new Object[]{"abc", "bc"},
            new Object[]{"def", "ef"},
            new Object[]{"1", NULL_VALUE},
            new Object[]{"2", NULL_VALUE},
            new Object[]{"", NULL_VALUE}
        )
    );
  }

  @Test
  public void testUnionAll() throws Exception
  {
    testQuery(
        "SELECT COUNT(*) FROM foo UNION ALL SELECT SUM(cnt) FROM foo UNION ALL SELECT COUNT(*) FROM foo",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build(),
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build(),
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(new Object[]{6L}, new Object[]{6L}, new Object[]{6L})
    );
  }

  @Test
  public void testUnionAllWithLimit() throws Exception
  {
    testQuery(
        "SELECT * FROM ("
        + "SELECT COUNT(*) FROM foo UNION ALL SELECT SUM(cnt) FROM foo UNION ALL SELECT COUNT(*) FROM foo"
        + ") LIMIT 2",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build(),
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(new Object[]{6L}, new Object[]{6L})
    );
  }

  @Test
  public void testPruneDeadAggregators() throws Exception
  {
    // Test for ProjectAggregatePruneUnusedCallRule.

    testQuery(
        "SELECT\n"
        + "  CASE 'foo'\n"
        + "  WHEN 'bar' THEN SUM(cnt)\n"
        + "  WHEN 'foo' THEN SUM(m1)\n"
        + "  WHEN 'baz' THEN SUM(m2)\n"
        + "  END\n"
        + "FROM foo",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(new DoubleSumAggregatorFactory("a0", "m1")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(new Object[]{21.0})
    );
  }

  @Test
  public void testPruneDeadAggregatorsThroughPostProjection() throws Exception
  {
    // Test for ProjectAggregatePruneUnusedCallRule.

    testQuery(
        "SELECT\n"
        + "  CASE 'foo'\n"
        + "  WHEN 'bar' THEN SUM(cnt) / 10\n"
        + "  WHEN 'foo' THEN SUM(m1) / 10\n"
        + "  WHEN 'baz' THEN SUM(m2) / 10\n"
        + "  END\n"
        + "FROM foo",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(new DoubleSumAggregatorFactory("a0", "m1")))
                  .postAggregators(ImmutableList.of(expressionPostAgg("p0", "(\"a0\" / 10)")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(new Object[]{2.1})
    );
  }

  @Test
  public void testPruneDeadAggregatorsThroughHaving() throws Exception
  {
    // Test for ProjectAggregatePruneUnusedCallRule.

    testQuery(
        "SELECT\n"
        + "  CASE 'foo'\n"
        + "  WHEN 'bar' THEN SUM(cnt)\n"
        + "  WHEN 'foo' THEN SUM(m1)\n"
        + "  WHEN 'baz' THEN SUM(m2)\n"
        + "  END AS theCase\n"
        + "FROM foo\n"
        + "HAVING theCase = 21",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setAggregatorSpecs(aggregators(new DoubleSumAggregatorFactory("a0", "m1")))
                        .setHavingSpec(having(selector("a0", "21", null)))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(new Object[]{21.0})
    );
  }

  @Test
  public void testGroupByCaseWhen() throws Exception
  {
    // Cannot vectorize due to virtual columns.
    cannotVectorize();

    testQuery(
        "SELECT\n"
        + "  CASE EXTRACT(DAY FROM __time)\n"
        + "    WHEN m1 THEN 'match-m1'\n"
        + "    WHEN cnt THEN 'match-cnt'\n"
        + "    WHEN 0 THEN 'zero'"
        + "    END,"
        + "  COUNT(*)\n"
        + "FROM druid.foo\n"
        + "GROUP BY"
        + "  CASE EXTRACT(DAY FROM __time)\n"
        + "    WHEN m1 THEN 'match-m1'\n"
        + "    WHEN cnt THEN 'match-cnt'\n"
        + "    WHEN 0 THEN 'zero'"
        + "    END",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(
                            expressionVirtualColumn(
                                "v0",
                                "case_searched("
                                + "(CAST(timestamp_extract(\"__time\",'DAY','UTC'), 'DOUBLE') == \"m1\"),"
                                + "'match-m1',"
                                + "(timestamp_extract(\"__time\",'DAY','UTC') == \"cnt\"),"
                                + "'match-cnt',"
                                + "(timestamp_extract(\"__time\",'DAY','UTC') == 0),"
                                + "'zero',"
                                + DruidExpression.nullLiteral() + ")",
                                ValueType.STRING
                            )
                        )
                        .setDimensions(dimensions(new DefaultDimensionSpec("v0", "v0")))
                        .setAggregatorSpecs(aggregators(new CountAggregatorFactory("a0")))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{NullHandling.defaultStringValue(), 2L},
            new Object[]{"match-cnt", 1L},
            new Object[]{"match-m1", 3L}
        )
    );
  }

  @Test
  public void testGroupByCaseWhenOfTripleAnd() throws Exception
  {
    // Cannot vectorize due to virtual columns.
    cannotVectorize();

    testQuery(
        "SELECT\n"
        + "  CASE WHEN m1 > 1 AND m1 < 5 AND cnt = 1 THEN 'x' ELSE NULL END,"
        + "  COUNT(*)\n"
        + "FROM druid.foo\n"
        + "GROUP BY 1",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(
                            expressionVirtualColumn(
                                "v0",
                                "case_searched(((\"m1\" > 1) && (\"m1\" < 5) && (\"cnt\" == 1)),'x',null)",
                                ValueType.STRING
                            )
                        )
                        .setDimensions(dimensions(new DefaultDimensionSpec("v0", "v0")))
                        .setAggregatorSpecs(aggregators(new CountAggregatorFactory("a0")))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{NullHandling.defaultStringValue(), 3L},
            new Object[]{"x", 3L}
        )
    );
  }

  @Test
  public void testNullEmptyStringEquality() throws Exception
  {
    testQuery(
        "SELECT COUNT(*)\n"
        + "FROM druid.foo\n"
        + "WHERE NULLIF(dim2, 'a') IS NULL",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .filters(expressionFilter("case_searched((\"dim2\" == 'a'),1,isnull(\"dim2\"))"))
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            NullHandling.replaceWithDefault() ?
            // Matches everything but "abc"
            new Object[]{5L} :
            // match only null values
            new Object[]{4L}
        )
    );
  }

  @Test
  public void testEmptyStringEquality() throws Exception
  {
    testQuery(
        "SELECT COUNT(*)\n"
        + "FROM druid.foo\n"
        + "WHERE NULLIF(dim2, 'a') = ''",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .filters(expressionFilter("case_searched((\"dim2\" == 'a'),"
                                            + (NullHandling.replaceWithDefault() ? "1" : "0")
                                            + ",(\"dim2\" == ''))"))
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            NullHandling.replaceWithDefault() ?
            // Matches everything but "abc"
            new Object[]{5L} :
            // match only empty string
            new Object[]{1L}
        )
    );
  }

  @Test
  public void testNullStringEquality() throws Exception
  {
    testQuery(
        "SELECT COUNT(*)\n"
        + "FROM druid.foo\n"
        + "WHERE NULLIF(dim2, 'a') = null",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .filters(expressionFilter("case_searched((\"dim2\" == 'a'),"
                                            + (NullHandling.replaceWithDefault() ? "1" : "0")
                                            + ",(\"dim2\" == null))"))
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        NullHandling.replaceWithDefault() ?
        // Matches everything but "abc"
        ImmutableList.of(new Object[]{5L}) :
        // null is not eqaual to null or any other value
        ImmutableList.of()
    );

  }

  @Test
  public void testCoalesceColumns() throws Exception
  {
    // Doesn't conform to the SQL standard, but it's how we do it.
    // This example is used in the sql.md doc.

    // Cannot vectorize due to virtual columns.
    cannotVectorize();

    testQuery(
        "SELECT COALESCE(dim2, dim1), COUNT(*) FROM druid.foo GROUP BY COALESCE(dim2, dim1)\n",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(
                            expressionVirtualColumn(
                                "v0",
                                "case_searched(notnull(\"dim2\"),\"dim2\",\"dim1\")",
                                ValueType.STRING
                            )
                        )
                        .setDimensions(dimensions(new DefaultDimensionSpec("v0", "v0", ValueType.STRING)))
                        .setAggregatorSpecs(aggregators(new CountAggregatorFactory("a0")))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        NullHandling.replaceWithDefault() ?
        ImmutableList.of(
            new Object[]{"10.1", 1L},
            new Object[]{"2", 1L},
            new Object[]{"a", 2L},
            new Object[]{"abc", 2L}
        ) :
        ImmutableList.of(
            new Object[]{"", 1L},
            new Object[]{"10.1", 1L},
            new Object[]{"a", 2L},
            new Object[]{"abc", 2L}
        )
    );
  }

  @Test
  public void testColumnIsNull() throws Exception
  {
    // Doesn't conform to the SQL standard, but it's how we do it.
    // This example is used in the sql.md doc.

    testQuery(
        "SELECT COUNT(*) FROM druid.foo WHERE dim2 IS NULL\n",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .filters(selector("dim2", null, null))
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{NullHandling.replaceWithDefault() ? 3L : 2L}
        )
    );
  }

  @Test
  public void testUnplannableQueries()
  {
    // All of these queries are unplannable because they rely on features Druid doesn't support.
    // This test is here to confirm that we don't fall back to Calcite's interpreter or enumerable implementation.
    // It's also here so when we do support these features, we can have "real" tests for these queries.

    final List<String> queries = ImmutableList.of(
        "SELECT dim1 FROM druid.foo ORDER BY dim1", // SELECT query with order by non-__time
        "SELECT COUNT(*) FROM druid.foo x, druid.foo y", // Self-join
        "SELECT DISTINCT dim2 FROM druid.foo ORDER BY dim2 LIMIT 2 OFFSET 5", // DISTINCT with OFFSET
        "SELECT COUNT(*) FROM foo WHERE dim1 NOT IN (SELECT dim1 FROM foo WHERE dim2 = 'a')", // NOT IN subquery
        "EXPLAIN PLAN FOR SELECT COUNT(*) FROM foo WHERE dim1 IN (SELECT dim1 FROM foo WHERE dim2 = 'a')\n"
        + "AND dim1 IN (SELECT dim1 FROM foo WHERE m2 > 2)" // AND of two IN subqueries
    );

    for (final String query : queries) {
      assertQueryIsUnplannable(query);
    }
  }

  @Test
  public void testUnplannableExactCountDistinctQueries()
  {
    // All of these queries are unplannable in exact COUNT DISTINCT mode.

    final List<String> queries = ImmutableList.of(
        "SELECT COUNT(distinct dim1), COUNT(distinct dim2) FROM druid.foo", // two COUNT DISTINCTs, same query
        "SELECT dim1, COUNT(distinct dim1), COUNT(distinct dim2) FROM druid.foo GROUP BY dim1", // two COUNT DISTINCTs
        "SELECT COUNT(distinct unique_dim1) FROM druid.foo" // COUNT DISTINCT on sketch cannot be exact
    );

    for (final String query : queries) {
      assertQueryIsUnplannable(PLANNER_CONFIG_NO_HLL, query);
    }
  }

  @Test
  public void testSelectStarWithDimFilter() throws Exception
  {
    testQuery(
        PLANNER_CONFIG_DEFAULT_NO_COMPLEX_SERDE,
        QUERY_CONTEXT_DEFAULT,
        "SELECT * FROM druid.foo WHERE dim1 > 'd' OR dim2 = 'a'",
        CalciteTests.REGULAR_USER_AUTH_RESULT,
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE1)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .filters(
                    or(
                        bound("dim1", "d", null, true, false, null, StringComparators.LEXICOGRAPHIC),
                        selector("dim2", "a", null)
                    )
                )
                .columns("__time", "cnt", "dim1", "dim2", "dim3", "m1", "m2", "unique_dim1")
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{timestamp("2000-01-01"), 1L, "", "a", "[\"a\",\"b\"]", 1.0f, 1.0d, HLLC_STRING},
            new Object[]{timestamp("2001-01-01"), 1L, "1", "a", "", 4.0f, 4.0d, HLLC_STRING},
            new Object[]{timestamp("2001-01-02"), 1L, "def", "abc", NULL_VALUE, 5.0f, 5.0d, HLLC_STRING}
        )
    );
  }

  @Test
  public void testGroupByNothingWithLiterallyFalseFilter() throws Exception
  {
    testQuery(
        "SELECT COUNT(*), MAX(cnt) FROM druid.foo WHERE 1 = 0",
        ImmutableList.of(),
        ImmutableList.of(
            new Object[]{0L, null}
        )
    );
  }

  @Test
  public void testGroupByNothingWithImpossibleTimeFilter() throws Exception
  {
    // Regression test for https://github.com/apache/incubator-druid/issues/7671

    testQuery(
        "SELECT COUNT(*) FROM druid.foo\n"
        + "WHERE FLOOR(__time TO DAY) = TIMESTAMP '2000-01-02 01:00:00'\n"
        + "OR FLOOR(__time TO DAY) = TIMESTAMP '2000-01-02 02:00:00'",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec())
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of()
    );
  }

  @Test
  public void testGroupByOneColumnWithLiterallyFalseFilter() throws Exception
  {
    testQuery(
        "SELECT COUNT(*), MAX(cnt) FROM druid.foo WHERE 1 = 0 GROUP BY dim1",
        ImmutableList.of(),
        ImmutableList.of()
    );
  }

  @Test
  public void testGroupByWithFilterMatchingNothing() throws Exception
  {
    // This query should actually return [0, null] rather than an empty result set, but it doesn't.
    // This test just "documents" the current behavior.

    // Cannot vectorize due to "longMax" aggregator.
    cannotVectorize();

    testQuery(
        "SELECT COUNT(*), MAX(cnt) FROM druid.foo WHERE dim1 = 'foobar'",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .filters(selector("dim1", "foobar", null))
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(
                      new CountAggregatorFactory("a0"),
                      new LongMaxAggregatorFactory("a1", "cnt")
                  ))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of()
    );
  }

  @Test
  public void testGroupByWithFilterMatchingNothingWithGroupByLiteral() throws Exception
  {
    // Cannot vectorize due to "longMax" aggregator.
    cannotVectorize();

    testQuery(
        "SELECT COUNT(*), MAX(cnt) FROM druid.foo WHERE dim1 = 'foobar' GROUP BY 'dummy'",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .filters(selector("dim1", "foobar", null))
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(
                      new CountAggregatorFactory("a0"),
                      new LongMaxAggregatorFactory("a1", "cnt")
                  ))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of()
    );
  }

  @Test
  public void testCountNonNullColumn() throws Exception
  {
    testQuery(
        "SELECT COUNT(cnt) FROM druid.foo",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{6L}
        )
    );
  }

  @Test
  public void testCountNullableColumn() throws Exception
  {
    testQuery(
        "SELECT COUNT(dim2) FROM druid.foo",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(
                      new FilteredAggregatorFactory(
                          new CountAggregatorFactory("a0"),
                          not(selector("dim2", null, null))
                      )
                  ))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        NullHandling.replaceWithDefault() ?
        ImmutableList.of(
            new Object[]{3L}
        ) :
        ImmutableList.of(
            new Object[]{4L}
        )
    );
  }

  @Test
  public void testCountNullableExpression() throws Exception
  {
    // Cannot vectorize due to expression filter.
    cannotVectorize();

    testQuery(
        "SELECT COUNT(CASE WHEN dim2 = 'abc' THEN 'yes' WHEN dim2 = 'def' THEN 'yes' END) FROM druid.foo",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .virtualColumns(
                      expressionVirtualColumn(
                          "v0",
                          "case_searched((\"dim2\" == 'abc'),'yes',(\"dim2\" == 'def'),'yes',"
                          + DruidExpression.nullLiteral()
                          + ")",
                          ValueType.STRING
                      )
                  )
                  .aggregators(aggregators(
                      new FilteredAggregatorFactory(
                          new CountAggregatorFactory("a0"),
                          not(selector("v0", NullHandling.defaultStringValue(), null))
                      )
                  ))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{1L}
        )
    );
  }

  @Test
  public void testCountStar() throws Exception
  {
    testQuery(
        "SELECT COUNT(*) FROM druid.foo",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{6L}
        )
    );
  }

  @Test
  public void testCountStarOnCommonTableExpression() throws Exception
  {
    testQuery(
        "WITH beep (dim1_firstchar) AS (SELECT SUBSTRING(dim1, 1, 1) FROM foo WHERE dim2 = 'a')\n"
        + "SELECT COUNT(*) FROM beep WHERE dim1_firstchar <> 'z'",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .filters(and(
                      selector("dim2", "a", null),
                      not(selector("dim1", "z", new SubstringDimExtractionFn(0, 1)))
                  ))
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{2L}
        )
    );
  }

  @Test
  public void testCountStarOnView() throws Exception
  {
    testQuery(
        "SELECT COUNT(*) FROM druid.aview WHERE dim1_firstchar <> 'z'",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .filters(and(
                      selector("dim2", "a", null),
                      not(selector("dim1", "z", new SubstringDimExtractionFn(0, 1)))
                  ))
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{2L}
        )
    );
  }

  @Test
  public void testExplainCountStarOnView() throws Exception
  {
    // Skip vectorization since otherwise the "context" will change for each subtest.
    skipVectorize();

    final String explanation =
        "DruidQueryRel(query=[{"
        + "\"queryType\":\"timeseries\","
        + "\"dataSource\":{\"type\":\"table\",\"name\":\"foo\"},"
        + "\"intervals\":{\"type\":\"intervals\",\"intervals\":[\"-146136543-09-08T08:23:32.096Z/146140482-04-24T15:36:27.903Z\"]},"
        + "\"descending\":false,"
        + "\"virtualColumns\":[],"
        + "\"filter\":{\"type\":\"and\",\"fields\":[{\"type\":\"selector\",\"dimension\":\"dim2\",\"value\":\"a\",\"extractionFn\":null},{\"type\":\"not\",\"field\":{\"type\":\"selector\",\"dimension\":\"dim1\",\"value\":\"z\",\"extractionFn\":{\"type\":\"substring\",\"index\":0,\"length\":1}}}]},"
        + "\"granularity\":{\"type\":\"all\"},"
        + "\"aggregations\":[{\"type\":\"count\",\"name\":\"a0\"}],"
        + "\"postAggregations\":[],"
        + "\"limit\":2147483647,"
        + "\"context\":{\"defaultTimeout\":300000,\"maxScatterGatherBytes\":9223372036854775807,\"skipEmptyBuckets\":true,\"sqlCurrentTimestamp\":\"2000-01-01T00:00:00Z\",\"sqlQueryId\":\"dummy\",\"vectorize\":\"false\"}}]"
        + ", signature=[{a0:LONG}])\n";

    testQuery(
        "EXPLAIN PLAN FOR SELECT COUNT(*) FROM aview WHERE dim1_firstchar <> 'z'",
        ImmutableList.of(),
        ImmutableList.of(
            new Object[]{explanation}
        )
    );
  }

  @Test
  public void testCountStarWithLikeFilter() throws Exception
  {
    testQuery(
        "SELECT COUNT(*) FROM druid.foo WHERE dim1 like 'a%' OR dim2 like '%xb%' escape 'x'",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .filters(
                      or(
                          new LikeDimFilter("dim1", "a%", null, null),
                          new LikeDimFilter("dim2", "%xb%", "x", null)
                      )
                  )
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{2L}
        )
    );
  }

  @Test
  public void testCountStarWithLongColumnFilters() throws Exception
  {
    testQuery(
        "SELECT COUNT(*) FROM druid.foo WHERE cnt >= 3 OR cnt = 1",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .filters(
                      or(
                          bound("cnt", "3", null, false, false, null, StringComparators.NUMERIC),
                          selector("cnt", "1", null)
                      )
                  )
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{6L}
        )
    );
  }

  @Test
  public void testCountStarWithLongColumnFiltersOnFloatLiterals() throws Exception
  {
    testQuery(
        "SELECT COUNT(*) FROM druid.foo WHERE cnt > 1.1 and cnt < 100000001.0",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .filters(
                      bound("cnt", "1.1", "100000001.0", true, true, null, StringComparators.NUMERIC)
                  )
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of()
    );

    testQuery(
        "SELECT COUNT(*) FROM druid.foo WHERE cnt = 1.0",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .filters(
                      selector("cnt", "1.0", null)
                  )
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{6L}
        )
    );

    testQuery(
        "SELECT COUNT(*) FROM druid.foo WHERE cnt = 100000001.0",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .filters(
                      selector("cnt", "100000001.0", null)
                  )
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of()
    );

    testQuery(
        "SELECT COUNT(*) FROM druid.foo WHERE cnt = 1.0 or cnt = 100000001.0",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .filters(
                      in("cnt", ImmutableList.of("1.0", "100000001.0"), null)
                  )
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{6L}
        )
    );
  }

  @Test
  public void testCountStarWithLongColumnFiltersOnTwoPoints() throws Exception
  {
    testQuery(
        "SELECT COUNT(*) FROM druid.foo WHERE cnt = 1 OR cnt = 2",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .filters(in("cnt", ImmutableList.of("1", "2"), null))
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{6L}
        )
    );
  }

  @Test
  public void testFilterOnStringAsNumber() throws Exception
  {
    // Cannot vectorize due to usage of expressions.
    cannotVectorize();

    testQuery(
        "SELECT distinct dim1 FROM druid.foo WHERE "
        + "dim1 = 10 OR "
        + "(floor(CAST(dim1 AS float)) = 10.00 and CAST(dim1 AS float) > 9 and CAST(dim1 AS float) <= 10.5)",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimensions(dimensions(new DefaultDimensionSpec("dim1", "d0")))
                        .setVirtualColumns(
                            expressionVirtualColumn(
                                "v0",
                                "floor(CAST(\"dim1\", 'DOUBLE'))",
                                ValueType.DOUBLE
                            )
                        )
                        .setDimFilter(
                            or(
                                selector("dim1", "10", null),
                                and(
                                    selector("v0", "10.00", null),
                                    bound("dim1", "9", "10.5", true, false, null, StringComparators.NUMERIC)
                                )
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{"10.1"}
        )
    );
  }

  @Test
  public void testSimpleAggregations() throws Exception
  {
    // Cannot vectorize due to "longMax" aggregator.
    cannotVectorize();

    testQuery(
        "SELECT COUNT(*), COUNT(cnt), COUNT(dim1), AVG(cnt), SUM(cnt), SUM(cnt) + MIN(cnt) + MAX(cnt), COUNT(dim2) FROM druid.foo",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .aggregators(
                      aggregators(
                          new CountAggregatorFactory("a0"),
                          new FilteredAggregatorFactory(
                              new CountAggregatorFactory("a1"),
                              not(selector("dim1", null, null))
                          ),
                          new LongSumAggregatorFactory("a2:sum", "cnt"),
                          new CountAggregatorFactory("a2:count"),
                          new LongSumAggregatorFactory("a3", "cnt"),
                          new LongMinAggregatorFactory("a4", "cnt"),
                          new LongMaxAggregatorFactory("a5", "cnt"),
                          new FilteredAggregatorFactory(
                              new CountAggregatorFactory("a6"),
                              not(selector("dim2", null, null))
                          )
                      )
                  )
                  .postAggregators(
                      new ArithmeticPostAggregator(
                          "a2",
                          "quotient",
                          ImmutableList.of(
                              new FieldAccessPostAggregator(null, "a2:sum"),
                              new FieldAccessPostAggregator(null, "a2:count")
                          )
                      ),
                      expressionPostAgg("p0", "((\"a3\" + \"a4\") + \"a5\")")
                  )
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        NullHandling.replaceWithDefault() ?
        ImmutableList.of(
            new Object[]{6L, 6L, 5L, 1L, 6L, 8L, 3L}
        ) :
        ImmutableList.of(
            new Object[]{6L, 6L, 6L, 1L, 6L, 8L, 4L}
        )
    );
  }

  @Test
  public void testGroupByWithSortOnPostAggregationDefault() throws Exception
  {
    // By default this query uses topN.

    testQuery(
        "SELECT dim1, MIN(m1) + MAX(m1) AS x FROM druid.foo GROUP BY dim1 ORDER BY x LIMIT 3",
        ImmutableList.of(
            new TopNQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE1)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .granularity(Granularities.ALL)
                .dimension(new DefaultDimensionSpec("dim1", "d0"))
                .metric(new InvertedTopNMetricSpec(new NumericTopNMetricSpec("p0")))
                .aggregators(
                    new FloatMinAggregatorFactory("a0", "m1"),
                    new FloatMaxAggregatorFactory("a1", "m1")
                )
                .postAggregators(expressionPostAgg("p0", "(\"a0\" + \"a1\")"))
                .threshold(3)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"", 2.0f},
            new Object[]{"10.1", 4.0f},
            new Object[]{"2", 6.0f}
        )
    );
  }

  @Test
  public void testGroupByWithSortOnPostAggregationNoTopNConfig() throws Exception
  {
    // Use PlannerConfig to disable topN, so this query becomes a groupBy.

    // Cannot vectorize due to "floatMin", "floatMax" aggregators.
    cannotVectorize();

    testQuery(
        PLANNER_CONFIG_NO_TOPN,
        "SELECT dim1, MIN(m1) + MAX(m1) AS x FROM druid.foo GROUP BY dim1 ORDER BY x LIMIT 3",
        CalciteTests.REGULAR_USER_AUTH_RESULT,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimensions(dimensions(new DefaultDimensionSpec("dim1", "d0")))
                        .setAggregatorSpecs(
                            new FloatMinAggregatorFactory("a0", "m1"),
                            new FloatMaxAggregatorFactory("a1", "m1")
                        )
                        .setPostAggregatorSpecs(ImmutableList.of(expressionPostAgg("p0", "(\"a0\" + \"a1\")")))
                        .setLimitSpec(
                            new DefaultLimitSpec(
                                ImmutableList.of(
                                    new OrderByColumnSpec(
                                        "p0",
                                        OrderByColumnSpec.Direction.ASCENDING,
                                        StringComparators.NUMERIC
                                    )
                                ),
                                3
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{"", 2.0f},
            new Object[]{"10.1", 4.0f},
            new Object[]{"2", 6.0f}
        )
    );
  }

  @Test
  public void testGroupByWithSortOnPostAggregationNoTopNContext() throws Exception
  {
    // Use context to disable topN, so this query becomes a groupBy.

    // Cannot vectorize due to "floatMin", "floatMax" aggregators.
    cannotVectorize();

    testQuery(
        PLANNER_CONFIG_DEFAULT,
        QUERY_CONTEXT_NO_TOPN,
        "SELECT dim1, MIN(m1) + MAX(m1) AS x FROM druid.foo GROUP BY dim1 ORDER BY x LIMIT 3",
        CalciteTests.REGULAR_USER_AUTH_RESULT,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimensions(dimensions(new DefaultDimensionSpec("dim1", "d0")))
                        .setAggregatorSpecs(
                            new FloatMinAggregatorFactory("a0", "m1"),
                            new FloatMaxAggregatorFactory("a1", "m1")
                        )
                        .setPostAggregatorSpecs(
                            ImmutableList.of(
                                expressionPostAgg("p0", "(\"a0\" + \"a1\")")
                            )
                        )
                        .setLimitSpec(
                            new DefaultLimitSpec(
                                ImmutableList.of(
                                    new OrderByColumnSpec(
                                        "p0",
                                        OrderByColumnSpec.Direction.ASCENDING,
                                        StringComparators.NUMERIC
                                    )
                                ),
                                3
                            )
                        )
                        .setContext(QUERY_CONTEXT_NO_TOPN)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{"", 2.0f},
            new Object[]{"10.1", 4.0f},
            new Object[]{"2", 6.0f}
        )
    );
  }

  @Test
  public void testFilteredAggregations() throws Exception
  {
    // Cannot vectorize due to "cardinality", "longMax" aggregators.
    cannotVectorize();

    testQuery(
        "SELECT "
        + "SUM(case dim1 when 'abc' then cnt end), "
        + "SUM(case dim1 when 'abc' then null else cnt end), "
        + "SUM(case substring(dim1, 1, 1) when 'a' then cnt end), "
        + "COUNT(dim2) filter(WHERE dim1 <> '1'), "
        + "COUNT(CASE WHEN dim1 <> '1' THEN 'dummy' END), "
        + "SUM(CASE WHEN dim1 <> '1' THEN 1 ELSE 0 END), "
        + "SUM(cnt) filter(WHERE dim2 = 'a'), "
        + "SUM(case when dim1 <> '1' then cnt end) filter(WHERE dim2 = 'a'), "
        + "SUM(CASE WHEN dim1 <> '1' THEN cnt ELSE 0 END), "
        + "MAX(CASE WHEN dim1 <> '1' THEN cnt END), "
        + "COUNT(DISTINCT CASE WHEN dim1 <> '1' THEN m1 END), "
        + "SUM(cnt) filter(WHERE dim2 = 'a' AND dim1 = 'b') "
        + "FROM druid.foo",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(
                      new FilteredAggregatorFactory(
                          new LongSumAggregatorFactory("a0", "cnt"),
                          selector("dim1", "abc", null)
                      ),
                      new FilteredAggregatorFactory(
                          new LongSumAggregatorFactory("a1", "cnt"),
                          not(selector("dim1", "abc", null))
                      ),
                      new FilteredAggregatorFactory(
                          new LongSumAggregatorFactory("a2", "cnt"),
                          selector("dim1", "a", new SubstringDimExtractionFn(0, 1))
                      ),
                      new FilteredAggregatorFactory(
                          new CountAggregatorFactory("a3"),
                          and(
                              not(selector("dim2", null, null)),
                              not(selector("dim1", "1", null))
                          )
                      ),
                      new FilteredAggregatorFactory(
                          new CountAggregatorFactory("a4"),
                          not(selector("dim1", "1", null))
                      ),
                      new FilteredAggregatorFactory(
                          new CountAggregatorFactory("a5"),
                          not(selector("dim1", "1", null))
                      ),
                      new FilteredAggregatorFactory(
                          new LongSumAggregatorFactory("a6", "cnt"),
                          selector("dim2", "a", null)
                      ),
                      new FilteredAggregatorFactory(
                          new LongSumAggregatorFactory("a7", "cnt"),
                          and(
                              selector("dim2", "a", null),
                              not(selector("dim1", "1", null))
                          )
                      ),
                      new FilteredAggregatorFactory(
                          new LongSumAggregatorFactory("a8", "cnt"),
                          not(selector("dim1", "1", null))
                      ),
                      new FilteredAggregatorFactory(
                          new LongMaxAggregatorFactory("a9", "cnt"),
                          not(selector("dim1", "1", null))
                      ),
                      new FilteredAggregatorFactory(
                          new CardinalityAggregatorFactory(
                              "a10",
                              null,
                              dimensions(new DefaultDimensionSpec("m1", "m1", ValueType.FLOAT)),
                              false,
                              true
                          ),
                          not(selector("dim1", "1", null))
                      ),
                      new FilteredAggregatorFactory(
                          new LongSumAggregatorFactory("a11", "cnt"),
                          and(selector("dim2", "a", null), selector("dim1", "b", null))
                      )
                  ))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        NullHandling.replaceWithDefault() ?
        ImmutableList.of(
            new Object[]{1L, 5L, 1L, 2L, 5L, 5L, 2L, 1L, 5L, 1L, 5L, 0L}
        ) :
        ImmutableList.of(
            new Object[]{1L, 5L, 1L, 3L, 5L, 5L, 2L, 1L, 5L, 1L, 5L, null}
        )
    );
  }

  @Test
  public void testCaseFilteredAggregationWithGroupBy() throws Exception
  {
    testQuery(
        "SELECT\n"
        + "  cnt,\n"
        + "  SUM(CASE WHEN dim1 <> '1' THEN 1 ELSE 0 END) + SUM(cnt)\n"
        + "FROM druid.foo\n"
        + "GROUP BY cnt",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimensions(dimensions(new DefaultDimensionSpec("cnt", "d0", ValueType.LONG)))
                        .setAggregatorSpecs(aggregators(
                            new FilteredAggregatorFactory(
                                new CountAggregatorFactory("a0"),
                                not(selector("dim1", "1", null))
                            ),
                            new LongSumAggregatorFactory("a1", "cnt")
                        ))
                        .setPostAggregatorSpecs(ImmutableList.of(expressionPostAgg("p0", "(\"a0\" + \"a1\")")))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{1L, 11L}
        )
    );
  }

  @Test
  public void testFilteredAggregationWithNotIn() throws Exception
  {
    testQuery(
        "SELECT\n"
        + "COUNT(*) filter(WHERE dim1 NOT IN ('1')),\n"
        + "COUNT(dim2) filter(WHERE dim1 NOT IN ('1'))\n"
        + "FROM druid.foo",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .aggregators(
                      aggregators(
                          new FilteredAggregatorFactory(
                              new CountAggregatorFactory("a0"),
                              not(selector("dim1", "1", null))
                          ),
                          new FilteredAggregatorFactory(
                              new CountAggregatorFactory("a1"),
                              and(
                                  not(selector("dim2", null, null)),
                                  not(selector("dim1", "1", null))
                              )
                          )
                      )
                  )
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        NullHandling.replaceWithDefault() ?
        ImmutableList.of(
            new Object[]{5L, 2L}
        ) :
        ImmutableList.of(
            new Object[]{5L, 3L}
        )
    );
  }

  @Test
  public void testExpressionAggregations() throws Exception
  {
    // Cannot vectorize due to "doubleMax" aggregator.
    cannotVectorize();

    final ExprMacroTable macroTable = CalciteTests.createExprMacroTable();

    testQuery(
        "SELECT\n"
        + "  SUM(cnt * 3),\n"
        + "  LN(SUM(cnt) + SUM(m1)),\n"
        + "  MOD(SUM(cnt), 4),\n"
        + "  SUM(CHARACTER_LENGTH(CAST(cnt * 10 AS VARCHAR))),\n"
        + "  MAX(CHARACTER_LENGTH(dim2) + LN(m1))\n"
        + "FROM druid.foo",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(
                      new LongSumAggregatorFactory("a0", null, "(\"cnt\" * 3)", macroTable),
                      new LongSumAggregatorFactory("a1", "cnt"),
                      new DoubleSumAggregatorFactory("a2", "m1"),
                      new LongSumAggregatorFactory("a3", null, "strlen(CAST((\"cnt\" * 10), 'STRING'))", macroTable),
                      new DoubleMaxAggregatorFactory("a4", null, "(strlen(\"dim2\") + log(\"m1\"))", macroTable)
                  ))
                  .postAggregators(
                      expressionPostAgg("p0", "log((\"a1\" + \"a2\"))"),
                      expressionPostAgg("p1", "(\"a1\" % 4)")
                  )
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{18L, 3.295836866004329, 2, 12L, 3f + (Math.log(5.0))}
        )
    );
  }

  @Test
  public void testExpressionFilteringAndGrouping() throws Exception
  {
    // Cannot vectorize due to virtual columns.
    cannotVectorize();

    testQuery(
        "SELECT\n"
        + "  FLOOR(m1 / 2) * 2,\n"
        + "  COUNT(*)\n"
        + "FROM druid.foo\n"
        + "WHERE FLOOR(m1 / 2) * 2 > -1\n"
        + "GROUP BY FLOOR(m1 / 2) * 2\n"
        + "ORDER BY 1 DESC",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(
                            expressionVirtualColumn("v0", "(floor((\"m1\" / 2)) * 2)", ValueType.FLOAT)
                        )
                        .setDimFilter(bound("v0", "-1", null, true, false, null, StringComparators.NUMERIC))
                        .setDimensions(dimensions(new DefaultDimensionSpec("v0", "v0", ValueType.FLOAT)))
                        .setAggregatorSpecs(aggregators(new CountAggregatorFactory("a0")))
                        .setLimitSpec(
                            new DefaultLimitSpec(
                                ImmutableList.of(
                                    new OrderByColumnSpec(
                                        "v0",
                                        OrderByColumnSpec.Direction.DESCENDING,
                                        StringComparators.NUMERIC
                                    )
                                ),
                                Integer.MAX_VALUE
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{6.0f, 1L},
            new Object[]{4.0f, 2L},
            new Object[]{2.0f, 2L},
            new Object[]{0.0f, 1L}
        )
    );
  }

  @Test
  public void testExpressionFilteringAndGroupingUsingCastToLong() throws Exception
  {
    // Cannot vectorize due to virtual columns.
    cannotVectorize();

    testQuery(
        "SELECT\n"
        + "  CAST(m1 AS BIGINT) / 2 * 2,\n"
        + "  COUNT(*)\n"
        + "FROM druid.foo\n"
        + "WHERE CAST(m1 AS BIGINT) / 2 * 2 > -1\n"
        + "GROUP BY CAST(m1 AS BIGINT) / 2 * 2\n"
        + "ORDER BY 1 DESC",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(
                            expressionVirtualColumn("v0", "((CAST(\"m1\", 'LONG') / 2) * 2)", ValueType.LONG)
                        )
                        .setDimFilter(
                            bound("v0", "-1", null, true, false, null, StringComparators.NUMERIC)
                        )
                        .setDimensions(dimensions(new DefaultDimensionSpec("v0", "v0", ValueType.LONG)))
                        .setAggregatorSpecs(aggregators(new CountAggregatorFactory("a0")))
                        .setLimitSpec(
                            new DefaultLimitSpec(
                                ImmutableList.of(
                                    new OrderByColumnSpec(
                                        "v0",
                                        OrderByColumnSpec.Direction.DESCENDING,
                                        StringComparators.NUMERIC
                                    )
                                ),
                                Integer.MAX_VALUE
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{6L, 1L},
            new Object[]{4L, 2L},
            new Object[]{2L, 2L},
            new Object[]{0L, 1L}
        )
    );
  }

  @Test
  public void testExpressionFilteringAndGroupingOnStringCastToNumber() throws Exception
  {
    // Cannot vectorize due to virtual columns.
    cannotVectorize();

    testQuery(
        "SELECT\n"
        + "  FLOOR(CAST(dim1 AS FLOAT) / 2) * 2,\n"
        + "  COUNT(*)\n"
        + "FROM druid.foo\n"
        + "WHERE FLOOR(CAST(dim1 AS FLOAT) / 2) * 2 > -1\n"
        + "GROUP BY FLOOR(CAST(dim1 AS FLOAT) / 2) * 2\n"
        + "ORDER BY 1 DESC",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(
                            expressionVirtualColumn(
                                "v0",
                                "(floor((CAST(\"dim1\", 'DOUBLE') / 2)) * 2)",
                                ValueType.FLOAT
                            )
                        )
                        .setDimFilter(
                            bound("v0", "-1", null, true, false, null, StringComparators.NUMERIC)
                        )
                        .setDimensions(dimensions(new DefaultDimensionSpec("v0", "v0", ValueType.FLOAT)))
                        .setAggregatorSpecs(aggregators(new CountAggregatorFactory("a0")))
                        .setLimitSpec(
                            new DefaultLimitSpec(
                                ImmutableList.of(
                                    new OrderByColumnSpec(
                                        "v0",
                                        OrderByColumnSpec.Direction.DESCENDING,
                                        StringComparators.NUMERIC
                                    )
                                ),
                                Integer.MAX_VALUE
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        NullHandling.replaceWithDefault() ?
        ImmutableList.of(
            new Object[]{10.0f, 1L},
            new Object[]{2.0f, 1L},
            new Object[]{0.0f, 4L}
        ) :
        ImmutableList.of(
            new Object[]{10.0f, 1L},
            new Object[]{2.0f, 1L},
            new Object[]{0.0f, 1L}
        )
    );
  }

  @Test
  public void testInFilter() throws Exception
  {
    testQuery(
        "SELECT dim1, COUNT(*) FROM druid.foo WHERE dim1 IN ('abc', 'def', 'ghi') GROUP BY dim1",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimensions(dimensions(new DefaultDimensionSpec("dim1", "d0")))
                        .setDimFilter(new InDimFilter("dim1", ImmutableList.of("abc", "def", "ghi"), null))
                        .setAggregatorSpecs(
                            aggregators(
                                new CountAggregatorFactory("a0")
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{"abc", 1L},
            new Object[]{"def", 1L}
        )
    );
  }

  @Test
  public void testInFilterWith23Elements() throws Exception
  {
    // Regression test for https://github.com/apache/incubator-druid/issues/4203.

    final List<String> elements = new ArrayList<>();
    elements.add("abc");
    elements.add("def");
    elements.add("ghi");
    for (int i = 0; i < 20; i++) {
      elements.add("dummy" + i);
    }

    final String elementsString = Joiner.on(",").join(elements.stream().map(s -> "'" + s + "'").iterator());

    testQuery(
        "SELECT dim1, COUNT(*) FROM druid.foo WHERE dim1 IN (" + elementsString + ") GROUP BY dim1",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimensions(dimensions(new DefaultDimensionSpec("dim1", "d0")))
                        .setDimFilter(new InDimFilter("dim1", elements, null))
                        .setAggregatorSpecs(
                            aggregators(
                                new CountAggregatorFactory("a0")
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{"abc", 1L},
            new Object[]{"def", 1L}
        )
    );
  }

  @Test
  public void testCountStarWithDegenerateFilter() throws Exception
  {
    testQuery(
        "SELECT COUNT(*) FROM druid.foo WHERE dim2 = 'a' and (dim1 > 'a' OR dim1 < 'b')",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .filters(
                      and(
                          selector("dim2", "a", null),
                          or(
                              bound("dim1", "a", null, true, false, null, StringComparators.LEXICOGRAPHIC),
                              not(selector("dim1", null, null))
                          )
                      )
                  )
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{NullHandling.sqlCompatible() ? 2L : 1L}
        )
    );
  }

  @Test
  public void testCountStarWithNotOfDegenerateFilter() throws Exception
  {
    testQuery(
        "SELECT COUNT(*) FROM druid.foo WHERE dim2 = 'a' and not (dim1 > 'a' OR dim1 < 'b')",
        ImmutableList.of(),
        ImmutableList.of(new Object[]{0L})
    );
  }

  @Test
  public void testCountStarWithBoundFilterSimplifyOnMetric() throws Exception
  {
    testQuery(
        "SELECT COUNT(*) FROM druid.foo WHERE 2.5 < m1 AND m1 < 3.5",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .filters(bound("m1", "2.5", "3.5", true, true, null, StringComparators.NUMERIC))
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{1L}
        )
    );
  }

  @Test
  public void testCountStarWithBoundFilterSimplifyOr() throws Exception
  {
    testQuery(
        "SELECT COUNT(*) FROM druid.foo WHERE (dim1 >= 'a' and dim1 < 'b') OR dim1 = 'ab'",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .filters(bound("dim1", "a", "b", false, true, null, StringComparators.LEXICOGRAPHIC))
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{1L}
        )
    );
  }

  @Test
  public void testCountStarWithBoundFilterSimplifyAnd() throws Exception
  {
    testQuery(
        "SELECT COUNT(*) FROM druid.foo WHERE (dim1 >= 'a' and dim1 < 'b') and dim1 = 'abc'",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .filters(selector("dim1", "abc", null))
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{1L}
        )
    );
  }

  @Test
  public void testCountStarWithFilterOnCastedString() throws Exception
  {
    testQuery(
        "SELECT COUNT(*) FROM druid.foo WHERE CAST(dim1 AS bigint) = 2",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .filters(numericSelector("dim1", "2", null))
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{1L}
        )
    );
  }

  @Test
  public void testCountStarWithTimeFilter() throws Exception
  {
    testQuery(
        "SELECT COUNT(*) FROM druid.foo "
        + "WHERE __time >= TIMESTAMP '2000-01-01 00:00:00' AND __time < TIMESTAMP '2001-01-01 00:00:00'",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Intervals.of("2000-01-01/2001-01-01")))
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{3L}
        )
    );
  }

  @Test
  public void testRemoveUselessCaseWhen() throws Exception
  {
    testQuery(
        "SELECT COUNT(*) FROM druid.foo\n"
        + "WHERE\n"
        + "  CASE\n"
        + "    WHEN __time >= TIME_PARSE('2000-01-01 00:00:00', 'yyyy-MM-dd HH:mm:ss') AND __time < TIMESTAMP '2001-01-01 00:00:00'\n"
        + "    THEN true\n"
        + "    ELSE false\n"
        + "  END\n"
        + "OR\n"
        + "  __time >= TIMESTAMP '2010-01-01 00:00:00' AND __time < TIMESTAMP '2011-01-01 00:00:00'",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Intervals.of("2000/2001"), Intervals.of("2010/2011")))
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{3L}
        )
    );
  }

  @Test
  public void testCountStarWithTimeMillisecondFilters() throws Exception
  {
    testQuery(
        "SELECT COUNT(*) FROM druid.foo\n"
        + "WHERE __time = TIMESTAMP '2000-01-01 00:00:00.111'\n"
        + "OR (__time >= TIMESTAMP '2000-01-01 00:00:00.888' AND __time < TIMESTAMP '2000-01-02 00:00:00.222')",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(
                      querySegmentSpec(
                          Intervals.of("2000-01-01T00:00:00.111/2000-01-01T00:00:00.112"),
                          Intervals.of("2000-01-01T00:00:00.888/2000-01-02T00:00:00.222")
                      )
                  )
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{1L}
        )
    );
  }

  @Test
  public void testCountStarWithTimeFilterUsingStringLiterals() throws Exception
  {
    // Strings are implicitly cast to timestamps. Test a few different forms.

    testQuery(
        "SELECT COUNT(*) FROM druid.foo\n"
        + "WHERE __time >= '2000-01-01 00:00:00' AND __time < '2001-01-01T00:00:00'\n"
        + "OR __time >= '2001-02-01' AND __time < '2001-02-02'\n"
        + "OR __time BETWEEN '2001-03-01' AND '2001-03-02'",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(
                      querySegmentSpec(
                          Intervals.of("2000-01-01/2001-01-01"),
                          Intervals.of("2001-02-01/2001-02-02"),
                          Intervals.of("2001-03-01/2001-03-02T00:00:00.001")
                      )
                  )
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{3L}
        )
    );
  }

  @Test
  public void testCountStarWithTimeFilterUsingStringLiteralsInvalid() throws Exception
  {
    // Strings are implicitly cast to timestamps. Test an invalid string.

    // This error message isn't ideal but it is at least better than silently ignoring the problem.
    expectedException.expect(RuntimeException.class);
    expectedException.expectMessage("Error while applying rule ReduceExpressionsRule");
    expectedException.expectCause(
        ThrowableMessageMatcher.hasMessage(CoreMatchers.containsString("Illegal TIMESTAMP constant"))
    );

    testQuery(
        "SELECT COUNT(*) FROM druid.foo\n"
        + "WHERE __time >= 'z2000-01-01 00:00:00' AND __time < '2001-01-01 00:00:00'\n",
        ImmutableList.of(),
        ImmutableList.of()
    );
  }

  @Test
  public void testCountStarWithSinglePointInTime() throws Exception
  {
    testQuery(
        "SELECT COUNT(*) FROM druid.foo WHERE __time = TIMESTAMP '2000-01-01 00:00:00'",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Intervals.of("2000-01-01/2000-01-01T00:00:00.001")))
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{1L}
        )
    );
  }

  @Test
  public void testCountStarWithTwoPointsInTime() throws Exception
  {
    testQuery(
        "SELECT COUNT(*) FROM druid.foo WHERE "
        + "__time = TIMESTAMP '2000-01-01 00:00:00' OR __time = TIMESTAMP '2000-01-01 00:00:00' + INTERVAL '1' DAY",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(
                      querySegmentSpec(
                          Intervals.of("2000-01-01/2000-01-01T00:00:00.001"),
                          Intervals.of("2000-01-02/2000-01-02T00:00:00.001")
                      )
                  )
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{2L}
        )
    );
  }

  @Test
  public void testCountStarWithComplexDisjointTimeFilter() throws Exception
  {
    testQuery(
        "SELECT COUNT(*) FROM druid.foo "
        + "WHERE dim2 = 'a' and ("
        + "  (__time >= TIMESTAMP '2000-01-01 00:00:00' AND __time < TIMESTAMP '2001-01-01 00:00:00')"
        + "  OR ("
        + "    (__time >= TIMESTAMP '2002-01-01 00:00:00' AND __time < TIMESTAMP '2003-05-01 00:00:00')"
        + "    and (__time >= TIMESTAMP '2002-05-01 00:00:00' AND __time < TIMESTAMP '2004-01-01 00:00:00')"
        + "    and dim1 = 'abc'"
        + "  )"
        + ")",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Intervals.of("2000/2001"), Intervals.of("2002-05-01/2003-05-01")))
                  .granularity(Granularities.ALL)
                  .filters(
                      and(
                          selector("dim2", "a", null),
                          or(
                              timeBound("2000/2001"),
                              and(
                                  selector("dim1", "abc", null),
                                  timeBound("2002-05-01/2003-05-01")
                              )
                          )
                      )
                  )
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{1L}
        )
    );
  }

  @Test
  public void testCountStarWithNotOfComplexDisjointTimeFilter() throws Exception
  {
    testQuery(
        "SELECT COUNT(*) FROM druid.foo "
        + "WHERE not (dim2 = 'a' and ("
        + "    (__time >= TIMESTAMP '2000-01-01 00:00:00' AND __time < TIMESTAMP '2001-01-01 00:00:00')"
        + "    OR ("
        + "      (__time >= TIMESTAMP '2002-01-01 00:00:00' AND __time < TIMESTAMP '2004-01-01 00:00:00')"
        + "      and (__time >= TIMESTAMP '2002-05-01 00:00:00' AND __time < TIMESTAMP '2003-05-01 00:00:00')"
        + "      and dim1 = 'abc'"
        + "    )"
        + "  )"
        + ")",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .filters(
                      or(
                          not(selector("dim2", "a", null)),
                          and(
                              not(timeBound("2000/2001")),
                              not(and(
                                  selector("dim1", "abc", null),
                                  timeBound("2002-05-01/2003-05-01")
                              ))
                          )
                      )
                  )
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{5L}
        )
    );
  }

  @Test
  public void testCountStarWithNotTimeFilter() throws Exception
  {
    testQuery(
        "SELECT COUNT(*) FROM druid.foo "
        + "WHERE dim1 <> 'xxx' and not ("
        + "    (__time >= TIMESTAMP '2000-01-01 00:00:00' AND __time < TIMESTAMP '2001-01-01 00:00:00')"
        + "    OR (__time >= TIMESTAMP '2003-01-01 00:00:00' AND __time < TIMESTAMP '2004-01-01 00:00:00'))",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(
                      querySegmentSpec(
                          new Interval(DateTimes.MIN, DateTimes.of("2000")),
                          Intervals.of("2001/2003"),
                          new Interval(DateTimes.of("2004"), DateTimes.MAX)
                      )
                  )
                  .filters(not(selector("dim1", "xxx", null)))
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{3L}
        )
    );
  }

  @Test
  public void testCountStarWithTimeAndDimFilter() throws Exception
  {
    testQuery(
        "SELECT COUNT(*) FROM druid.foo "
        + "WHERE dim2 <> 'a' "
        + "and __time BETWEEN TIMESTAMP '2000-01-01 00:00:00' AND TIMESTAMP '2000-12-31 23:59:59.999'",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Intervals.of("2000-01-01/2001-01-01")))
                  .filters(not(selector("dim2", "a", null)))
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{2L}
        )
    );
  }

  @Test
  public void testCountStarWithTimeOrDimFilter() throws Exception
  {
    testQuery(
        "SELECT COUNT(*) FROM druid.foo "
        + "WHERE dim2 <> 'a' "
        + "or __time BETWEEN TIMESTAMP '2000-01-01 00:00:00' AND TIMESTAMP '2000-12-31 23:59:59.999'",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .filters(
                      or(
                          not(selector("dim2", "a", null)),
                          bound(
                              "__time",
                              String.valueOf(timestamp("2000-01-01")),
                              String.valueOf(timestamp("2000-12-31T23:59:59.999")),
                              false,
                              false,
                              null,
                              StringComparators.NUMERIC
                          )
                      )
                  )
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{5L}
        )
    );
  }

  @Test
  public void testCountStarWithTimeFilterOnLongColumnUsingExtractEpoch() throws Exception
  {
    testQuery(
        "SELECT COUNT(*) FROM druid.foo WHERE "
        + "cnt >= EXTRACT(EPOCH FROM TIMESTAMP '1970-01-01 00:00:00') * 1000 "
        + "AND cnt < EXTRACT(EPOCH FROM TIMESTAMP '1970-01-02 00:00:00') * 1000",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .filters(
                      bound(
                          "cnt",
                          String.valueOf(DateTimes.of("1970-01-01").getMillis()),
                          String.valueOf(DateTimes.of("1970-01-02").getMillis()),
                          false,
                          true,
                          null,
                          StringComparators.NUMERIC
                      )
                  )
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{6L}
        )
    );
  }

  @Test
  public void testCountStarWithTimeFilterOnLongColumnUsingExtractEpochFromDate() throws Exception
  {
    testQuery(
        "SELECT COUNT(*) FROM druid.foo WHERE "
        + "cnt >= EXTRACT(EPOCH FROM DATE '1970-01-01') * 1000 "
        + "AND cnt < EXTRACT(EPOCH FROM DATE '1970-01-02') * 1000",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .filters(
                      bound(
                          "cnt",
                          String.valueOf(DateTimes.of("1970-01-01").getMillis()),
                          String.valueOf(DateTimes.of("1970-01-02").getMillis()),
                          false,
                          true,
                          null,
                          StringComparators.NUMERIC
                      )
                  )
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{6L}
        )
    );
  }

  @Test
  public void testCountStarWithTimeFilterOnLongColumnUsingTimestampToMillis() throws Exception
  {
    testQuery(
        "SELECT COUNT(*) FROM druid.foo WHERE "
        + "cnt >= TIMESTAMP_TO_MILLIS(TIMESTAMP '1970-01-01 00:00:00') "
        + "AND cnt < TIMESTAMP_TO_MILLIS(TIMESTAMP '1970-01-02 00:00:00')",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .filters(
                      bound(
                          "cnt",
                          String.valueOf(DateTimes.of("1970-01-01").getMillis()),
                          String.valueOf(DateTimes.of("1970-01-02").getMillis()),
                          false,
                          true,
                          null,
                          StringComparators.NUMERIC
                      )
                  )
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{6L}
        )
    );
  }

  @Test
  public void testSumOfString() throws Exception
  {
    // Cannot vectorize due to expressions in aggregators.
    cannotVectorize();

    testQuery(
        "SELECT SUM(CAST(dim1 AS INTEGER)) FROM druid.foo",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(
                      new LongSumAggregatorFactory(
                          "a0",
                          null,
                          "CAST(\"dim1\", 'LONG')",
                          CalciteTests.createExprMacroTable()
                      )
                  ))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{13L}
        )
    );
  }

  @Test
  public void testSumOfExtractionFn() throws Exception
  {
    // Cannot vectorize due to expressions in aggregators.
    cannotVectorize();

    testQuery(
        "SELECT SUM(CAST(SUBSTRING(dim1, 1, 10) AS INTEGER)) FROM druid.foo",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(
                      new LongSumAggregatorFactory(
                          "a0",
                          null,
                          "CAST(substring(\"dim1\", 0, 10), 'LONG')",
                          CalciteTests.createExprMacroTable()
                      )
                  ))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{13L}
        )
    );
  }

  @Test
  public void testTimeseriesWithTimeFilterOnLongColumnUsingMillisToTimestamp() throws Exception
  {
    // Cannot vectorize due to virtual columns.
    cannotVectorize();

    testQuery(
        "SELECT\n"
        + "  FLOOR(MILLIS_TO_TIMESTAMP(cnt) TO YEAR),\n"
        + "  COUNT(*)\n"
        + "FROM\n"
        + "  druid.foo\n"
        + "WHERE\n"
        + "  MILLIS_TO_TIMESTAMP(cnt) >= TIMESTAMP '1970-01-01 00:00:00'\n"
        + "  AND MILLIS_TO_TIMESTAMP(cnt) < TIMESTAMP '1970-01-02 00:00:00'\n"
        + "GROUP BY\n"
        + "  FLOOR(MILLIS_TO_TIMESTAMP(cnt) TO YEAR)",
        ImmutableList.of(
            new GroupByQuery.Builder()
                .setDataSource(CalciteTests.DATASOURCE1)
                .setInterval(querySegmentSpec(Filtration.eternity()))
                .setGranularity(Granularities.ALL)
                .setVirtualColumns(
                    expressionVirtualColumn("v0", "timestamp_floor(\"cnt\",'P1Y',null,'UTC')", ValueType.LONG)
                )
                .setDimFilter(
                    bound(
                        "cnt",
                        String.valueOf(DateTimes.of("1970-01-01").getMillis()),
                        String.valueOf(DateTimes.of("1970-01-02").getMillis()),
                        false,
                        true,
                        null,
                        StringComparators.NUMERIC
                    )
                )
                .setDimensions(dimensions(new DefaultDimensionSpec("v0", "v0", ValueType.LONG)))
                .setAggregatorSpecs(aggregators(new CountAggregatorFactory("a0")))
                .setContext(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{timestamp("1970-01-01"), 6L}
        )
    );
  }

  @Test
  public void testSelectDistinctWithCascadeExtractionFilter() throws Exception
  {
    testQuery(
        "SELECT distinct dim1 FROM druid.foo WHERE substring(substring(dim1, 2), 1, 1) = 'e' OR dim2 = 'a'",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimensions(dimensions(new DefaultDimensionSpec("dim1", "d0")))
                        .setDimFilter(
                            or(
                                selector(
                                    "dim1",
                                    "e",
                                    cascade(
                                        new SubstringDimExtractionFn(1, null),
                                        new SubstringDimExtractionFn(0, 1)
                                    )
                                ),
                                selector("dim2", "a", null)
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{""},
            new Object[]{"1"},
            new Object[]{"def"}
        )
    );
  }

  @Test
  public void testSelectDistinctWithStrlenFilter() throws Exception
  {
    // Cannot vectorize due to usage of expressions.
    cannotVectorize();

    testQuery(
        "SELECT distinct dim1 FROM druid.foo "
        + "WHERE CHARACTER_LENGTH(dim1) = 3 OR CAST(CHARACTER_LENGTH(dim1) AS varchar) = 3",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(
                            expressionVirtualColumn("v0", "strlen(\"dim1\")", ValueType.LONG),
                            expressionVirtualColumn("v1", "CAST(strlen(\"dim1\"), 'STRING')", ValueType.STRING)
                        )
                        .setDimensions(dimensions(new DefaultDimensionSpec("dim1", "d0")))
                        .setDimFilter(
                            or(
                                selector("v0", "3", null),
                                selector("v1", "3", null)
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{"abc"},
            new Object[]{"def"}
        )
    );
  }

  @Test
  public void testSelectDistinctWithLimit() throws Exception
  {
    // Should use topN even if approximate topNs are off, because this query is exact.

    testQuery(
        "SELECT DISTINCT dim2 FROM druid.foo LIMIT 10",
        ImmutableList.of(
            new TopNQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE1)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .granularity(Granularities.ALL)
                .dimension(new DefaultDimensionSpec("dim2", "d0"))
                .metric(new DimensionTopNMetricSpec(null, StringComparators.LEXICOGRAPHIC))
                .threshold(10)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        NullHandling.replaceWithDefault() ?
        ImmutableList.of(
            new Object[]{""},
            new Object[]{"a"},
            new Object[]{"abc"}
        ) :
        ImmutableList.of(
            new Object[]{null},
            new Object[]{""},
            new Object[]{"a"},
            new Object[]{"abc"}
        )
    );
  }

  @Test
  public void testSelectDistinctWithSortAsOuterQuery() throws Exception
  {
    testQuery(
        "SELECT * FROM (SELECT DISTINCT dim2 FROM druid.foo ORDER BY dim2) LIMIT 10",
        ImmutableList.of(
            new TopNQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE1)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .granularity(Granularities.ALL)
                .dimension(new DefaultDimensionSpec("dim2", "d0"))
                .metric(new DimensionTopNMetricSpec(null, StringComparators.LEXICOGRAPHIC))
                .threshold(10)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        NullHandling.replaceWithDefault() ?
        ImmutableList.of(
            new Object[]{""},
            new Object[]{"a"},
            new Object[]{"abc"}
        ) :
        ImmutableList.of(
            new Object[]{null},
            new Object[]{""},
            new Object[]{"a"},
            new Object[]{"abc"}
        )
    );
  }

  @Test
  public void testSelectDistinctWithSortAsOuterQuery2() throws Exception
  {
    testQuery(
        "SELECT * FROM (SELECT DISTINCT dim2 FROM druid.foo ORDER BY dim2 LIMIT 5) LIMIT 10",
        ImmutableList.of(
            new TopNQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE1)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .granularity(Granularities.ALL)
                .dimension(new DefaultDimensionSpec("dim2", "d0"))
                .metric(new DimensionTopNMetricSpec(null, StringComparators.LEXICOGRAPHIC))
                .threshold(5)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        NullHandling.replaceWithDefault() ?
        ImmutableList.of(
            new Object[]{""},
            new Object[]{"a"},
            new Object[]{"abc"}
        ) :
        ImmutableList.of(
            new Object[]{null},
            new Object[]{""},
            new Object[]{"a"},
            new Object[]{"abc"}
        )
    );
  }

  @Test
  public void testSelectDistinctWithSortAsOuterQuery3() throws Exception
  {
    // Query reduces to LIMIT 0.

    testQuery(
        "SELECT * FROM (SELECT DISTINCT dim2 FROM druid.foo ORDER BY dim2 LIMIT 2 OFFSET 5) OFFSET 2",
        ImmutableList.of(),
        ImmutableList.of()
    );
  }

  @Test
  public void testSelectDistinctWithSortAsOuterQuery4() throws Exception
  {
    testQuery(
        "SELECT * FROM (SELECT DISTINCT dim2 FROM druid.foo ORDER BY dim2 DESC LIMIT 5) LIMIT 10",
        ImmutableList.of(
            new TopNQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE1)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .granularity(Granularities.ALL)
                .dimension(new DefaultDimensionSpec("dim2", "d0"))
                .metric(new InvertedTopNMetricSpec(new DimensionTopNMetricSpec(null, StringComparators.LEXICOGRAPHIC)))
                .threshold(5)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        NullHandling.replaceWithDefault() ?
        ImmutableList.of(
            new Object[]{""},
            new Object[]{"abc"},
            new Object[]{"a"}
        ) :
        ImmutableList.of(
            new Object[]{null},
            new Object[]{"abc"},
            new Object[]{"a"},
            new Object[]{""}
        )
    );
  }

  @Test
  public void testCountDistinct() throws Exception
  {
    // Cannot vectorize due to "cardinality" aggregator.
    cannotVectorize();

    testQuery(
        "SELECT SUM(cnt), COUNT(distinct dim2), COUNT(distinct unique_dim1) FROM druid.foo",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .aggregators(
                      aggregators(
                          new LongSumAggregatorFactory("a0", "cnt"),
                          new CardinalityAggregatorFactory(
                              "a1",
                              null,
                              dimensions(new DefaultDimensionSpec("dim2", null)),
                              false,
                              true
                          ),
                          new HyperUniquesAggregatorFactory("a2", "unique_dim1", false, true)
                      )
                  )
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{6L, 3L, 6L}
        )
    );
  }

  @Test
  public void testCountDistinctOfCaseWhen() throws Exception
  {
    // Cannot vectorize due to "cardinality" aggregator.
    cannotVectorize();

    testQuery(
        "SELECT\n"
        + "COUNT(DISTINCT CASE WHEN m1 >= 4 THEN m1 END),\n"
        + "COUNT(DISTINCT CASE WHEN m1 >= 4 THEN dim1 END),\n"
        + "COUNT(DISTINCT CASE WHEN m1 >= 4 THEN unique_dim1 END)\n"
        + "FROM druid.foo",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .aggregators(
                      aggregators(
                          new FilteredAggregatorFactory(
                              new CardinalityAggregatorFactory(
                                  "a0",
                                  null,
                                  ImmutableList.of(new DefaultDimensionSpec("m1", "m1", ValueType.FLOAT)),
                                  false,
                                  true
                              ),
                              bound("m1", "4", null, false, false, null, StringComparators.NUMERIC)
                          ),
                          new FilteredAggregatorFactory(
                              new CardinalityAggregatorFactory(
                                  "a1",
                                  null,
                                  ImmutableList.of(new DefaultDimensionSpec("dim1", "dim1", ValueType.STRING)),
                                  false,
                                  true
                              ),
                              bound("m1", "4", null, false, false, null, StringComparators.NUMERIC)
                          ),
                          new FilteredAggregatorFactory(
                              new HyperUniquesAggregatorFactory("a2", "unique_dim1", false, true),
                              bound("m1", "4", null, false, false, null, StringComparators.NUMERIC)
                          )
                      )
                  )
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{3L, 3L, 3L}
        )
    );
  }

  @Test
  public void testExactCountDistinct() throws Exception
  {
    // When HLL is disabled, do exact count distinct through a nested query.

    testQuery(
        PLANNER_CONFIG_NO_HLL,
        "SELECT COUNT(distinct dim2) FROM druid.foo",
        CalciteTests.REGULAR_USER_AUTH_RESULT,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(
                            new QueryDataSource(
                                GroupByQuery.builder()
                                            .setDataSource(CalciteTests.DATASOURCE1)
                                            .setInterval(querySegmentSpec(Filtration.eternity()))
                                            .setGranularity(Granularities.ALL)
                                            .setDimensions(dimensions(new DefaultDimensionSpec("dim2", "d0")))
                                            .setContext(QUERY_CONTEXT_DEFAULT)
                                            .build()
                            )
                        )
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setAggregatorSpecs(aggregators(
                            new FilteredAggregatorFactory(
                                new CountAggregatorFactory("a0"),
                                not(selector("d0", null, null))
                            )
                        ))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{NullHandling.replaceWithDefault() ? 2L : 3L}
        )
    );
  }

  @Test
  public void testApproxCountDistinctWhenHllDisabled() throws Exception
  {
    // When HLL is disabled, APPROX_COUNT_DISTINCT is still approximate.

    // Cannot vectorize due to "cardinality" aggregator.
    cannotVectorize();

    testQuery(
        PLANNER_CONFIG_NO_HLL,
        "SELECT APPROX_COUNT_DISTINCT(dim2) FROM druid.foo",
        CalciteTests.REGULAR_USER_AUTH_RESULT,
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .aggregators(
                      aggregators(
                          new CardinalityAggregatorFactory(
                              "a0",
                              null,
                              dimensions(new DefaultDimensionSpec("dim2", null)),
                              false,
                              true
                          )
                      )
                  )
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{3L}
        )
    );
  }

  @Test
  public void testExactCountDistinctWithGroupingAndOtherAggregators() throws Exception
  {
    // When HLL is disabled, do exact count distinct through a nested query.

    testQuery(
        PLANNER_CONFIG_NO_HLL,
        "SELECT dim2, SUM(cnt), COUNT(distinct dim1) FROM druid.foo GROUP BY dim2",
        CalciteTests.REGULAR_USER_AUTH_RESULT,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(
                            new QueryDataSource(
                                GroupByQuery.builder()
                                            .setDataSource(CalciteTests.DATASOURCE1)
                                            .setInterval(querySegmentSpec(Filtration.eternity()))
                                            .setGranularity(Granularities.ALL)
                                            .setDimensions(dimensions(
                                                new DefaultDimensionSpec("dim2", "d0"),
                                                new DefaultDimensionSpec("dim1", "d1")
                                            ))
                                            .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                                            .setContext(QUERY_CONTEXT_DEFAULT)
                                            .build()
                            )
                        )
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimensions(dimensions(new DefaultDimensionSpec("d0", "_d0")))
                        .setAggregatorSpecs(aggregators(
                            new LongSumAggregatorFactory("_a0", "a0"),
                            new FilteredAggregatorFactory(
                                new CountAggregatorFactory("_a1"),
                                not(selector("d1", null, null))
                            )
                        ))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        NullHandling.replaceWithDefault() ?
        ImmutableList.of(
            new Object[]{"", 3L, 3L},
            new Object[]{"a", 2L, 1L},
            new Object[]{"abc", 1L, 1L}
        ) :
        ImmutableList.of(
            new Object[]{null, 2L, 2L},
            new Object[]{"", 1L, 1L},
            new Object[]{"a", 2L, 2L},
            new Object[]{"abc", 1L, 1L}
        )
    );
  }

  @Test
  public void testApproxCountDistinct() throws Exception
  {
    // Cannot vectorize due to virtual columns.
    cannotVectorize();

    testQuery(
        "SELECT\n"
        + "  SUM(cnt),\n"
        + "  APPROX_COUNT_DISTINCT(dim2),\n" // uppercase
        + "  approx_count_distinct(dim2) FILTER(WHERE dim2 <> ''),\n" // lowercase; also, filtered
        + "  APPROX_COUNT_DISTINCT(SUBSTRING(dim2, 1, 1)),\n" // on extractionFn
        + "  APPROX_COUNT_DISTINCT(SUBSTRING(dim2, 1, 1) || 'x'),\n" // on expression
        + "  approx_count_distinct(unique_dim1)\n" // on native hyperUnique column
        + "FROM druid.foo",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .virtualColumns(
                      expressionVirtualColumn("v0", "concat(substring(\"dim2\", 0, 1),'x')", ValueType.STRING)
                  )
                  .aggregators(
                      aggregators(
                          new LongSumAggregatorFactory("a0", "cnt"),
                          new CardinalityAggregatorFactory(
                              "a1",
                              null,
                              dimensions(new DefaultDimensionSpec("dim2", "dim2")),
                              false,
                              true
                          ),
                          new FilteredAggregatorFactory(
                              new CardinalityAggregatorFactory(
                                  "a2",
                                  null,
                                  dimensions(new DefaultDimensionSpec("dim2", "dim2")),
                                  false,
                                  true
                              ),
                              not(selector("dim2", "", null))
                          ),
                          new CardinalityAggregatorFactory(
                              "a3",
                              null,
                              dimensions(
                                  new ExtractionDimensionSpec(
                                      "dim2",
                                      "dim2",
                                      ValueType.STRING,
                                      new SubstringDimExtractionFn(0, 1)
                                  )
                              ),
                              false,
                              true
                          ),
                          new CardinalityAggregatorFactory(
                              "a4",
                              null,
                              dimensions(new DefaultDimensionSpec("v0", "v0", ValueType.STRING)),
                              false,
                              true
                          ),
                          new HyperUniquesAggregatorFactory("a5", "unique_dim1", false, true)
                      )
                  )
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        NullHandling.replaceWithDefault() ?
        ImmutableList.of(
            new Object[]{6L, 3L, 2L, 2L, 2L, 6L}
        ) :
        ImmutableList.of(
            new Object[]{6L, 3L, 2L, 1L, 1L, 6L}
        )
    );
  }

  @Test
  public void testNestedGroupBy() throws Exception
  {
    // Cannot vectorize due to virtual columns.
    cannotVectorize();

    testQuery(
        "SELECT\n"
        + "    FLOOR(__time to hour) AS __time,\n"
        + "    dim1,\n"
        + "    COUNT(m2)\n"
        + "FROM (\n"
        + "    SELECT\n"
        + "        MAX(__time) AS __time,\n"
        + "        m2,\n"
        + "        dim1\n"
        + "    FROM druid.foo\n"
        + "    WHERE 1=1\n"
        + "        AND m1 = '5.0'\n"
        + "    GROUP BY m2, dim1\n"
        + ")\n"
        + "GROUP BY FLOOR(__time to hour), dim1",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(
                            GroupByQuery.builder()
                                        .setDataSource(CalciteTests.DATASOURCE1)
                                        .setInterval(querySegmentSpec(Filtration.eternity()))
                                        .setGranularity(Granularities.ALL)
                                        .setDimensions(dimensions(
                                            new DefaultDimensionSpec("m2", "d0", ValueType.DOUBLE),
                                            new DefaultDimensionSpec("dim1", "d1")
                                        ))
                                        .setDimFilter(new SelectorDimFilter("m1", "5.0", null))
                                        .setAggregatorSpecs(aggregators(new LongMaxAggregatorFactory("a0", "__time")))
                                        .setContext(QUERY_CONTEXT_DEFAULT)
                                        .build()
                        )
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(
                            expressionVirtualColumn(
                                "v0",
                                "timestamp_floor(\"a0\",'PT1H',null,'UTC')",
                                ValueType.LONG
                            )
                        )
                        .setDimensions(dimensions(
                            new DefaultDimensionSpec("v0", "v0", ValueType.LONG),
                            new DefaultDimensionSpec("d1", "_d0", ValueType.STRING)
                        ))
                        .setAggregatorSpecs(aggregators(
                            new CountAggregatorFactory("_a0")
                        ))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{978393600000L, "def", 1L}
        )
    );
  }

  @Test
  public void testDoubleNestedGroupBy() throws Exception
  {
    testQuery(
        "SELECT SUM(cnt), COUNT(*) FROM (\n"
        + "  SELECT dim2, SUM(t1.cnt) cnt FROM (\n"
        + "    SELECT\n"
        + "      dim1,\n"
        + "      dim2,\n"
        + "      COUNT(*) cnt\n"
        + "    FROM druid.foo\n"
        + "    GROUP BY dim1, dim2\n"
        + "  ) t1\n"
        + "  GROUP BY dim2\n"
        + ") t2",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(
                            GroupByQuery.builder()
                                        .setDataSource(
                                            GroupByQuery.builder()
                                                        .setDataSource(CalciteTests.DATASOURCE1)
                                                        .setInterval(querySegmentSpec(Filtration.eternity()))
                                                        .setGranularity(Granularities.ALL)
                                                        .setDimensions(dimensions(
                                                            new DefaultDimensionSpec("dim1", "d0"),
                                                            new DefaultDimensionSpec("dim2", "d1")
                                                        ))
                                                        .setAggregatorSpecs(aggregators(new CountAggregatorFactory("a0")))
                                                        .setContext(QUERY_CONTEXT_DEFAULT)
                                                        .build()
                                        )
                                        .setInterval(querySegmentSpec(Filtration.eternity()))
                                        .setGranularity(Granularities.ALL)
                                        .setDimensions(dimensions(new DefaultDimensionSpec("d1", "_d0")))
                                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("_a0", "a0")))
                                        .setContext(QUERY_CONTEXT_DEFAULT)
                                        .build()
                        )
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setAggregatorSpecs(aggregators(
                            new LongSumAggregatorFactory("a0", "_a0"),
                            new CountAggregatorFactory("a1")
                        ))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        NullHandling.replaceWithDefault() ?
        ImmutableList.of(
            new Object[]{6L, 3L}
        ) :
        ImmutableList.of(
            new Object[]{6L, 4L}
        )
    );
  }

  @Test
  public void testExplainDoubleNestedGroupBy() throws Exception
  {
    // Skip vectorization since otherwise the "context" will change for each subtest.
    skipVectorize();

    final String explanation =
        "DruidOuterQueryRel(query=[{\"queryType\":\"timeseries\",\"dataSource\":{\"type\":\"table\",\"name\":\"__subquery__\"},\"intervals\":{\"type\":\"intervals\",\"intervals\":[\"-146136543-09-08T08:23:32.096Z/146140482-04-24T15:36:27.903Z\"]},\"descending\":false,\"virtualColumns\":[],\"filter\":null,\"granularity\":{\"type\":\"all\"},\"aggregations\":[{\"type\":\"longSum\",\"name\":\"a0\",\"fieldName\":\"cnt\",\"expression\":null},{\"type\":\"count\",\"name\":\"a1\"}],\"postAggregations\":[],\"limit\":2147483647,\"context\":{\"defaultTimeout\":300000,\"maxScatterGatherBytes\":9223372036854775807,\"skipEmptyBuckets\":true,\"sqlCurrentTimestamp\":\"2000-01-01T00:00:00Z\",\"sqlQueryId\":\"dummy\",\"vectorize\":\"false\"}}], signature=[{a0:LONG, a1:LONG}])\n"
        + "  DruidOuterQueryRel(query=[{\"queryType\":\"groupBy\",\"dataSource\":{\"type\":\"table\",\"name\":\"__subquery__\"},\"intervals\":{\"type\":\"intervals\",\"intervals\":[\"-146136543-09-08T08:23:32.096Z/146140482-04-24T15:36:27.903Z\"]},\"virtualColumns\":[],\"filter\":null,\"granularity\":{\"type\":\"all\"},\"dimensions\":[{\"type\":\"default\",\"dimension\":\"dim2\",\"outputName\":\"d0\",\"outputType\":\"STRING\"}],\"aggregations\":[{\"type\":\"longSum\",\"name\":\"a0\",\"fieldName\":\"cnt\",\"expression\":null}],\"postAggregations\":[],\"having\":null,\"limitSpec\":{\"type\":\"NoopLimitSpec\"},\"context\":{\"defaultTimeout\":300000,\"maxScatterGatherBytes\":9223372036854775807,\"sqlCurrentTimestamp\":\"2000-01-01T00:00:00Z\",\"sqlQueryId\":\"dummy\",\"vectorize\":\"false\"},\"descending\":false}], signature=[{d0:STRING, a0:LONG}])\n"
        + "    DruidQueryRel(query=[{\"queryType\":\"groupBy\",\"dataSource\":{\"type\":\"table\",\"name\":\"foo\"},\"intervals\":{\"type\":\"intervals\",\"intervals\":[\"-146136543-09-08T08:23:32.096Z/146140482-04-24T15:36:27.903Z\"]},\"virtualColumns\":[],\"filter\":null,\"granularity\":{\"type\":\"all\"},\"dimensions\":[{\"type\":\"default\",\"dimension\":\"dim1\",\"outputName\":\"d0\",\"outputType\":\"STRING\"},{\"type\":\"default\",\"dimension\":\"dim2\",\"outputName\":\"d1\",\"outputType\":\"STRING\"}],\"aggregations\":[{\"type\":\"count\",\"name\":\"a0\"}],\"postAggregations\":[],\"having\":null,\"limitSpec\":{\"type\":\"NoopLimitSpec\"},\"context\":{\"defaultTimeout\":300000,\"maxScatterGatherBytes\":9223372036854775807,\"sqlCurrentTimestamp\":\"2000-01-01T00:00:00Z\",\"sqlQueryId\":\"dummy\",\"vectorize\":\"false\"},\"descending\":false}], signature=[{d0:STRING, d1:STRING, a0:LONG}])\n";

    testQuery(
        "EXPLAIN PLAN FOR SELECT SUM(cnt), COUNT(*) FROM (\n"
        + "  SELECT dim2, SUM(t1.cnt) cnt FROM (\n"
        + "    SELECT\n"
        + "      dim1,\n"
        + "      dim2,\n"
        + "      COUNT(*) cnt\n"
        + "    FROM druid.foo\n"
        + "    GROUP BY dim1, dim2\n"
        + "  ) t1\n"
        + "  GROUP BY dim2\n"
        + ") t2",
        ImmutableList.of(),
        ImmutableList.of(
            new Object[]{explanation}
        )
    );
  }

  @Test
  public void testExactCountDistinctUsingSubquery() throws Exception
  {
    testQuery(
        PLANNER_CONFIG_SINGLE_NESTING_ONLY, // Sanity check; this query should work with a single level of nesting.
        "SELECT\n"
        + "  SUM(cnt),\n"
        + "  COUNT(*)\n"
        + "FROM (SELECT dim2, SUM(cnt) AS cnt FROM druid.foo GROUP BY dim2)",
        CalciteTests.REGULAR_USER_AUTH_RESULT,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(
                            new QueryDataSource(
                                GroupByQuery.builder()
                                            .setDataSource(CalciteTests.DATASOURCE1)
                                            .setInterval(querySegmentSpec(Filtration.eternity()))
                                            .setGranularity(Granularities.ALL)
                                            .setDimensions(dimensions(new DefaultDimensionSpec("dim2", "d0")))
                                            .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                                            .setContext(QUERY_CONTEXT_DEFAULT)
                                            .build()
                            )
                        )
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setAggregatorSpecs(aggregators(
                            new LongSumAggregatorFactory("_a0", "a0"),
                            new CountAggregatorFactory("_a1")
                        ))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        NullHandling.replaceWithDefault() ?
        ImmutableList.of(
            new Object[]{6L, 3L}
        ) :
        ImmutableList.of(
            new Object[]{6L, 4L}
        )
    );
  }

  @Test
  public void testMinMaxAvgDailyCountWithLimit() throws Exception
  {
    // Cannot vectorize due to virtual columns.
    cannotVectorize();

    testQuery(
        "SELECT * FROM ("
        + "  SELECT max(cnt), min(cnt), avg(cnt), TIME_EXTRACT(max(t), 'EPOCH') last_time, count(1) num_days FROM (\n"
        + "      SELECT TIME_FLOOR(__time, 'P1D') AS t, count(1) cnt\n"
        + "      FROM \"foo\"\n"
        + "      GROUP BY 1\n"
        + "  )"
        + ") LIMIT 1\n",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(
                            new QueryDataSource(
                                GroupByQuery.builder()
                                            .setDataSource(CalciteTests.DATASOURCE1)
                                            .setInterval(querySegmentSpec(Filtration.eternity()))
                                            .setGranularity(Granularities.ALL)
                                            .setVirtualColumns(
                                                expressionVirtualColumn(
                                                    "v0",
                                                    "timestamp_floor(\"__time\",'P1D',null,'UTC')",
                                                    ValueType.LONG
                                                )
                                            )
                                            .setDimensions(dimensions(new DefaultDimensionSpec(
                                                "v0",
                                                "v0",
                                                ValueType.LONG
                                            )))
                                            .setAggregatorSpecs(aggregators(new CountAggregatorFactory("a0")))
                                            .setContext(QUERY_CONTEXT_DEFAULT)
                                            .build()
                            )
                        )
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setAggregatorSpecs(aggregators(
                            new LongMaxAggregatorFactory("_a0", "a0"),
                            new LongMinAggregatorFactory("_a1", "a0"),
                            new LongSumAggregatorFactory("_a2:sum", "a0"),
                            new CountAggregatorFactory("_a2:count"),
                            new LongMaxAggregatorFactory("_a3", "v0"),
                            new CountAggregatorFactory("_a4")
                        ))
                        .setPostAggregatorSpecs(
                            ImmutableList.of(
                                new ArithmeticPostAggregator(
                                    "_a2",
                                    "quotient",
                                    ImmutableList.of(
                                        new FieldAccessPostAggregator(null, "_a2:sum"),
                                        new FieldAccessPostAggregator(null, "_a2:count")
                                    )
                                ),
                                expressionPostAgg("s0", "timestamp_extract(\"_a3\",'EPOCH','UTC')")
                            )
                        )
                        .setLimit(1)
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(new Object[]{1L, 1L, 1L, 978480000L, 6L})
    );
  }

  @Test
  public void testAvgDailyCountDistinct() throws Exception
  {
    // Cannot vectorize due to virtual columns.
    cannotVectorize();

    testQuery(
        "SELECT\n"
        + "  AVG(u)\n"
        + "FROM (SELECT FLOOR(__time TO DAY), APPROX_COUNT_DISTINCT(cnt) AS u FROM druid.foo GROUP BY 1)",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(
                            new QueryDataSource(
                                GroupByQuery.builder()
                                            .setDataSource(CalciteTests.DATASOURCE1)
                                            .setInterval(querySegmentSpec(Filtration.eternity()))
                                            .setGranularity(Granularities.ALL)
                                            .setVirtualColumns(
                                                expressionVirtualColumn(
                                                    "v0",
                                                    "timestamp_floor(\"__time\",'P1D',null,'UTC')",
                                                    ValueType.LONG
                                                )
                                            )
                                            .setDimensions(dimensions(new DefaultDimensionSpec(
                                                "v0",
                                                "v0",
                                                ValueType.LONG
                                            )))
                                            .setAggregatorSpecs(
                                                aggregators(
                                                    new CardinalityAggregatorFactory(
                                                        "a0:a",
                                                        null,
                                                        dimensions(new DefaultDimensionSpec(
                                                            "cnt",
                                                            "cnt",
                                                            ValueType.LONG
                                                        )),
                                                        false,
                                                        true
                                                    )
                                                )
                                            )
                                            .setPostAggregatorSpecs(
                                                ImmutableList.of(
                                                    new HyperUniqueFinalizingPostAggregator("a0", "a0:a")
                                                )
                                            )
                                            .setContext(QUERY_CONTEXT_DEFAULT)
                                            .build()
                            )
                        )
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setAggregatorSpecs(aggregators(
                            new LongSumAggregatorFactory("_a0:sum", "a0"),
                            new CountAggregatorFactory("_a0:count")
                        ))
                        .setPostAggregatorSpecs(
                            ImmutableList.of(
                                new ArithmeticPostAggregator(
                                    "_a0",
                                    "quotient",
                                    ImmutableList.of(
                                        new FieldAccessPostAggregator(null, "_a0:sum"),
                                        new FieldAccessPostAggregator(null, "_a0:count")
                                    )
                                )
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(new Object[]{1L})
    );
  }

  @Test
  public void testTopNFilterJoin() throws Exception
  {
    DimFilter filter = NullHandling.replaceWithDefault() ?
                       in("dim2", Arrays.asList(null, "a"), null)
                                                         : selector("dim2", "a", null);
    // Filters on top N values of some dimension by using an inner join.
    testQuery(
        "SELECT t1.dim1, SUM(t1.cnt)\n"
        + "FROM druid.foo t1\n"
        + "  INNER JOIN (\n"
        + "  SELECT\n"
        + "    SUM(cnt) AS sum_cnt,\n"
        + "    dim2\n"
        + "  FROM druid.foo\n"
        + "  GROUP BY dim2\n"
        + "  ORDER BY 1 DESC\n"
        + "  LIMIT 2\n"
        + ") t2 ON (t1.dim2 = t2.dim2)\n"
        + "GROUP BY t1.dim1\n"
        + "ORDER BY 1\n",
        ImmutableList.of(
            new TopNQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE1)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .granularity(Granularities.ALL)
                .dimension(new DefaultDimensionSpec("dim2", "d0"))
                .aggregators(new LongSumAggregatorFactory("a0", "cnt"))
                .metric(new NumericTopNMetricSpec("a0"))
                .threshold(2)
                .context(QUERY_CONTEXT_DEFAULT)
                .build(),
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimFilter(filter)
                        .setDimensions(dimensions(new DefaultDimensionSpec("dim1", "d0")))
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(
                            new DefaultLimitSpec(
                                ImmutableList.of(
                                    new OrderByColumnSpec(
                                        "d0",
                                        OrderByColumnSpec.Direction.ASCENDING,
                                        StringComparators.LEXICOGRAPHIC
                                    )
                                ),
                                Integer.MAX_VALUE
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        NullHandling.replaceWithDefault() ?
        ImmutableList.of(
            new Object[]{"", 1L},
            new Object[]{"1", 1L},
            new Object[]{"10.1", 1L},
            new Object[]{"2", 1L},
            new Object[]{"abc", 1L}
        ) :
        ImmutableList.of(
            new Object[]{"", 1L},
            new Object[]{"1", 1L}
        )
    );
  }

  @Test
  @Ignore // Doesn't work
  public void testTopNFilterJoinWithProjection() throws Exception
  {
    // Filters on top N values of some dimension by using an inner join. Also projects the outer dimension.

    testQuery(
        "SELECT SUBSTRING(t1.dim1, 1, 10), SUM(t1.cnt)\n"
        + "FROM druid.foo t1\n"
        + "  INNER JOIN (\n"
        + "  SELECT\n"
        + "    SUM(cnt) AS sum_cnt,\n"
        + "    dim2\n"
        + "  FROM druid.foo\n"
        + "  GROUP BY dim2\n"
        + "  ORDER BY 1 DESC\n"
        + "  LIMIT 2\n"
        + ") t2 ON (t1.dim2 = t2.dim2)\n"
        + "GROUP BY SUBSTRING(t1.dim1, 1, 10)",
        ImmutableList.of(
            new TopNQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE1)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .granularity(Granularities.ALL)
                .dimension(new DefaultDimensionSpec("dim2", "d0"))
                .aggregators(new LongSumAggregatorFactory("a0", "cnt"))
                .metric(new NumericTopNMetricSpec("a0"))
                .threshold(2)
                .context(QUERY_CONTEXT_DEFAULT)
                .build(),
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimFilter(in("dim2", ImmutableList.of("", "a"), null))
                        .setDimensions(dimensions(new DefaultDimensionSpec("dim1", "d0")))
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(
                            new DefaultLimitSpec(
                                ImmutableList.of(
                                    new OrderByColumnSpec(
                                        "d0",
                                        OrderByColumnSpec.Direction.ASCENDING,
                                        StringComparators.LEXICOGRAPHIC
                                    )
                                ),
                                Integer.MAX_VALUE
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{"", 1L},
            new Object[]{"1", 1L},
            new Object[]{"10.1", 1L},
            new Object[]{"2", 1L},
            new Object[]{"abc", 1L}
        )
    );
  }

  @Test
  public void testRemovableLeftJoin() throws Exception
  {
    // LEFT JOIN where the right-hand side can be ignored.

    testQuery(
        "SELECT t1.dim1, SUM(t1.cnt)\n"
        + "FROM druid.foo t1\n"
        + "  LEFT JOIN (\n"
        + "  SELECT\n"
        + "    SUM(cnt) AS sum_cnt,\n"
        + "    dim2\n"
        + "  FROM druid.foo\n"
        + "  GROUP BY dim2\n"
        + "  ORDER BY 1 DESC\n"
        + "  LIMIT 2\n"
        + ") t2 ON (t1.dim2 = t2.dim2)\n"
        + "GROUP BY t1.dim1\n"
        + "ORDER BY 1\n",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimensions(dimensions(new DefaultDimensionSpec("dim1", "d0")))
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(
                            new DefaultLimitSpec(
                                ImmutableList.of(
                                    new OrderByColumnSpec(
                                        "d0",
                                        OrderByColumnSpec.Direction.ASCENDING,
                                        StringComparators.LEXICOGRAPHIC
                                    )
                                ),
                                Integer.MAX_VALUE
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{"", 1L},
            new Object[]{"1", 1L},
            new Object[]{"10.1", 1L},
            new Object[]{"2", 1L},
            new Object[]{"abc", 1L},
            new Object[]{"def", 1L}
        )
    );
  }

  @Test
  public void testExactCountDistinctOfSemiJoinResult() throws Exception
  {
    // Cannot vectorize due to extraction dimension spec.
    cannotVectorize();

    testQuery(
        "SELECT COUNT(*)\n"
        + "FROM (\n"
        + "  SELECT DISTINCT dim2\n"
        + "  FROM druid.foo\n"
        + "  WHERE SUBSTRING(dim2, 1, 1) IN (\n"
        + "    SELECT SUBSTRING(dim1, 1, 1) FROM druid.foo WHERE dim1 <> ''\n"
        + "  ) AND __time >= '2000-01-01' AND __time < '2002-01-01'\n"
        + ")",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimFilter(not(selector("dim1", "", null)))
                        .setDimensions(dimensions(new ExtractionDimensionSpec(
                            "dim1",
                            "d0",
                            new SubstringDimExtractionFn(0, 1)
                        )))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build(),
            GroupByQuery.builder()
                        .setDataSource(
                            new QueryDataSource(
                                GroupByQuery.builder()
                                            .setDataSource(CalciteTests.DATASOURCE1)
                                            .setInterval(querySegmentSpec(Intervals.of("2000-01-01/2002-01-01")))
                                            .setGranularity(Granularities.ALL)
                                            .setDimFilter(in(
                                                "dim2",
                                                ImmutableList.of("1", "2", "a", "d"),
                                                new SubstringDimExtractionFn(0, 1)
                                            ))
                                            .setDimensions(dimensions(new DefaultDimensionSpec("dim2", "d0")))
                                            .setContext(QUERY_CONTEXT_DEFAULT)
                                            .build()
                            )
                        )
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setAggregatorSpecs(aggregators(
                            new CountAggregatorFactory("a0")
                        ))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{2L}
        )
    );
  }

  @Test
  public void testMaxSemiJoinRowsInMemory() throws Exception
  {
    expectedException.expect(ResourceLimitExceededException.class);
    expectedException.expectMessage("maxSemiJoinRowsInMemory[2] exceeded");
    testQuery(
        PLANNER_CONFIG_SEMI_JOIN_ROWS_LIMIT,
        "SELECT COUNT(*)\n"
        + "FROM druid.foo\n"
        + "WHERE SUBSTRING(dim2, 1, 1) IN (\n"
        + "  SELECT SUBSTRING(dim1, 1, 1) FROM druid.foo WHERE dim1 <> ''\n"
        + ")\n",
        CalciteTests.REGULAR_USER_AUTH_RESULT,
        ImmutableList.of(),
        ImmutableList.of()
    );
  }

  @Test
  public void testExplainExactCountDistinctOfSemiJoinResult() throws Exception
  {
    // Skip vectorization since otherwise the "context" will change for each subtest.
    skipVectorize();

    final String explanation =
        "DruidOuterQueryRel(query=[{\"queryType\":\"timeseries\",\"dataSource\":{\"type\":\"table\",\"name\":\"__subquery__\"},\"intervals\":{\"type\":\"intervals\",\"intervals\":[\"-146136543-09-08T08:23:32.096Z/146140482-04-24T15:36:27.903Z\"]},\"descending\":false,\"virtualColumns\":[],\"filter\":null,\"granularity\":{\"type\":\"all\"},\"aggregations\":[{\"type\":\"count\",\"name\":\"a0\"}],\"postAggregations\":[],\"limit\":2147483647,\"context\":{\"defaultTimeout\":300000,\"maxScatterGatherBytes\":9223372036854775807,\"skipEmptyBuckets\":true,\"sqlCurrentTimestamp\":\"2000-01-01T00:00:00Z\",\"sqlQueryId\":\"dummy\",\"vectorize\":\"false\"}}], signature=[{a0:LONG}])\n"
        + "  DruidSemiJoin(query=[{\"queryType\":\"groupBy\",\"dataSource\":{\"type\":\"table\",\"name\":\"foo\"},\"intervals\":{\"type\":\"intervals\",\"intervals\":[\"-146136543-09-08T08:23:32.096Z/146140482-04-24T15:36:27.903Z\"]},\"virtualColumns\":[],\"filter\":null,\"granularity\":{\"type\":\"all\"},\"dimensions\":[{\"type\":\"default\",\"dimension\":\"dim2\",\"outputName\":\"d0\",\"outputType\":\"STRING\"}],\"aggregations\":[],\"postAggregations\":[],\"having\":null,\"limitSpec\":{\"type\":\"NoopLimitSpec\"},\"context\":{\"defaultTimeout\":300000,\"maxScatterGatherBytes\":9223372036854775807,\"sqlCurrentTimestamp\":\"2000-01-01T00:00:00Z\",\"sqlQueryId\":\"dummy\",\"vectorize\":\"false\"},\"descending\":false}], leftExpressions=[[SUBSTRING($3, 1, 1)]], rightKeys=[[0]])\n"
        + "    DruidQueryRel(query=[{\"queryType\":\"groupBy\",\"dataSource\":{\"type\":\"table\",\"name\":\"foo\"},\"intervals\":{\"type\":\"intervals\",\"intervals\":[\"-146136543-09-08T08:23:32.096Z/146140482-04-24T15:36:27.903Z\"]},\"virtualColumns\":[],\"filter\":{\"type\":\"not\",\"field\":{\"type\":\"selector\",\"dimension\":\"dim1\",\"value\":null,\"extractionFn\":null}},\"granularity\":{\"type\":\"all\"},\"dimensions\":[{\"type\":\"extraction\",\"dimension\":\"dim1\",\"outputName\":\"d0\",\"outputType\":\"STRING\",\"extractionFn\":{\"type\":\"substring\",\"index\":0,\"length\":1}}],\"aggregations\":[],\"postAggregations\":[],\"having\":null,\"limitSpec\":{\"type\":\"NoopLimitSpec\"},\"context\":{\"defaultTimeout\":300000,\"maxScatterGatherBytes\":9223372036854775807,\"sqlCurrentTimestamp\":\"2000-01-01T00:00:00Z\",\"sqlQueryId\":\"dummy\",\"vectorize\":\"false\"},\"descending\":false}], signature=[{d0:STRING}])\n";

    testQuery(
        "EXPLAIN PLAN FOR SELECT COUNT(*)\n"
        + "FROM (\n"
        + "  SELECT DISTINCT dim2\n"
        + "  FROM druid.foo\n"
        + "  WHERE SUBSTRING(dim2, 1, 1) IN (\n"
        + "    SELECT SUBSTRING(dim1, 1, 1) FROM druid.foo WHERE dim1 IS NOT NULL\n"
        + "  )\n"
        + ")",
        ImmutableList.of(),
        ImmutableList.of(new Object[]{explanation})
    );
  }

  @Test
  public void testExactCountDistinctUsingSubqueryWithWherePushDown() throws Exception
  {
    testQuery(
        "SELECT\n"
        + "  SUM(cnt),\n"
        + "  COUNT(*)\n"
        + "FROM (SELECT dim2, SUM(cnt) AS cnt FROM druid.foo GROUP BY dim2)\n"
        + "WHERE dim2 <> ''",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(
                            new QueryDataSource(
                                GroupByQuery.builder()
                                            .setDataSource(CalciteTests.DATASOURCE1)
                                            .setInterval(querySegmentSpec(Filtration.eternity()))
                                            .setDimFilter(not(selector("dim2", "", null)))
                                            .setGranularity(Granularities.ALL)
                                            .setDimensions(dimensions(new DefaultDimensionSpec("dim2", "d0")))
                                            .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                                            .setContext(QUERY_CONTEXT_DEFAULT)
                                            .build()
                            )
                        )
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setAggregatorSpecs(aggregators(
                            new LongSumAggregatorFactory("_a0", "a0"),
                            new CountAggregatorFactory("_a1")
                        ))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        NullHandling.replaceWithDefault() ?
        ImmutableList.of(
            new Object[]{3L, 2L}
        ) :
        ImmutableList.of(
            new Object[]{5L, 3L}
        )
    );

    testQuery(
        "SELECT\n"
        + "  SUM(cnt),\n"
        + "  COUNT(*)\n"
        + "FROM (SELECT dim2, SUM(cnt) AS cnt FROM druid.foo GROUP BY dim2)\n"
        + "WHERE dim2 IS NOT NULL",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(
                            new QueryDataSource(
                                GroupByQuery.builder()
                                            .setDataSource(CalciteTests.DATASOURCE1)
                                            .setInterval(querySegmentSpec(Filtration.eternity()))
                                            .setDimFilter(not(selector("dim2", null, null)))
                                            .setGranularity(Granularities.ALL)
                                            .setDimensions(dimensions(new DefaultDimensionSpec("dim2", "d0")))
                                            .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                                            .setContext(QUERY_CONTEXT_DEFAULT)
                                            .build()
                            )
                        )
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setAggregatorSpecs(aggregators(
                            new LongSumAggregatorFactory("_a0", "a0"),
                            new CountAggregatorFactory("_a1")
                        ))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        NullHandling.replaceWithDefault() ?
        ImmutableList.of(
            new Object[]{3L, 2L}
        ) :
        ImmutableList.of(
            new Object[]{4L, 3L}
        )
    );
  }

  @Test
  public void testExactCountDistinctUsingSubqueryWithWhereToOuterFilter() throws Exception
  {
    testQuery(
        "SELECT\n"
        + "  SUM(cnt),\n"
        + "  COUNT(*)\n"
        + "FROM (SELECT dim2, SUM(cnt) AS cnt FROM druid.foo GROUP BY dim2 LIMIT 1)"
        + "WHERE cnt > 0",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(
                            new QueryDataSource(
                                GroupByQuery.builder()
                                            .setDataSource(CalciteTests.DATASOURCE1)
                                            .setInterval(querySegmentSpec(Filtration.eternity()))
                                            .setGranularity(Granularities.ALL)
                                            .setDimensions(dimensions(new DefaultDimensionSpec("dim2", "d0")))
                                            .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                                            .setLimit(1)
                                            .setContext(QUERY_CONTEXT_DEFAULT)
                                            .build()
                            )
                        )
                        .setDimFilter(bound("a0", "0", null, true, false, null, StringComparators.NUMERIC))
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setAggregatorSpecs(aggregators(
                            new LongSumAggregatorFactory("_a0", "a0"),
                            new CountAggregatorFactory("_a1")
                        ))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        NullHandling.replaceWithDefault() ?
        ImmutableList.of(
            new Object[]{3L, 1L}
        ) :
        ImmutableList.of(
            new Object[]{2L, 1L}
        )
    );
  }

  @Test
  public void testCompareExactAndApproximateCountDistinctUsingSubquery() throws Exception
  {
    testQuery(
        "SELECT\n"
        + "  COUNT(*) AS exact_count,\n"
        + "  COUNT(DISTINCT dim1) AS approx_count,\n"
        + "  (CAST(1 AS FLOAT) - COUNT(DISTINCT dim1) / COUNT(*)) * 100 AS error_pct\n"
        + "FROM (SELECT DISTINCT dim1 FROM druid.foo WHERE dim1 <> '')",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(
                            new QueryDataSource(
                                GroupByQuery.builder()
                                            .setDataSource(CalciteTests.DATASOURCE1)
                                            .setInterval(querySegmentSpec(Filtration.eternity()))
                                            .setGranularity(Granularities.ALL)
                                            .setDimFilter(not(selector("dim1", "", null)))
                                            .setDimensions(dimensions(new DefaultDimensionSpec("dim1", "d0")))
                                            .setContext(QUERY_CONTEXT_DEFAULT)
                                            .build()
                            )
                        )
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setAggregatorSpecs(aggregators(
                            new CountAggregatorFactory("a0"),
                            new CardinalityAggregatorFactory(
                                "a1",
                                null,
                                dimensions(new DefaultDimensionSpec("d0", null)),
                                false,
                                true
                            )
                        ))
                        .setPostAggregatorSpecs(
                            ImmutableList.of(
                                expressionPostAgg("p0", "((1 - (\"a1\" / \"a0\")) * 100)")
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{5L, 5L, 0.0f}
        )
    );
  }

  @Test
  public void testHistogramUsingSubquery() throws Exception
  {
    testQuery(
        "SELECT\n"
        + "  CAST(thecnt AS VARCHAR),\n"
        + "  COUNT(*)\n"
        + "FROM (SELECT dim2, SUM(cnt) AS thecnt FROM druid.foo GROUP BY dim2)\n"
        + "GROUP BY CAST(thecnt AS VARCHAR)",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(
                            new QueryDataSource(
                                GroupByQuery.builder()
                                            .setDataSource(CalciteTests.DATASOURCE1)
                                            .setInterval(querySegmentSpec(Filtration.eternity()))
                                            .setGranularity(Granularities.ALL)
                                            .setDimensions(dimensions(new DefaultDimensionSpec("dim2", "d0")))
                                            .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                                            .setContext(QUERY_CONTEXT_DEFAULT)
                                            .build()
                            )
                        )
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimensions(dimensions(new DefaultDimensionSpec("a0", "_d0")))
                        .setAggregatorSpecs(aggregators(
                            new CountAggregatorFactory("_a0")
                        ))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        NullHandling.replaceWithDefault() ?
        ImmutableList.of(
            new Object[]{"1", 1L},
            new Object[]{"2", 1L},
            new Object[]{"3", 1L}
        ) :
        ImmutableList.of(
            new Object[]{"1", 2L},
            new Object[]{"2", 2L}
        )
    );
  }

  @Test
  public void testHistogramUsingSubqueryWithSort() throws Exception
  {
    testQuery(
        "SELECT\n"
        + "  CAST(thecnt AS VARCHAR),\n"
        + "  COUNT(*)\n"
        + "FROM (SELECT dim2, SUM(cnt) AS thecnt FROM druid.foo GROUP BY dim2)\n"
        + "GROUP BY CAST(thecnt AS VARCHAR) ORDER BY CAST(thecnt AS VARCHAR) LIMIT 2",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(
                            new QueryDataSource(
                                GroupByQuery.builder()
                                            .setDataSource(CalciteTests.DATASOURCE1)
                                            .setInterval(querySegmentSpec(Filtration.eternity()))
                                            .setGranularity(Granularities.ALL)
                                            .setDimensions(dimensions(new DefaultDimensionSpec("dim2", "d0")))
                                            .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                                            .setContext(QUERY_CONTEXT_DEFAULT)
                                            .build()
                            )
                        )
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimensions(dimensions(new DefaultDimensionSpec("a0", "_d0")))
                        .setAggregatorSpecs(aggregators(
                            new CountAggregatorFactory("_a0")
                        ))
                        .setLimitSpec(
                            new DefaultLimitSpec(
                                ImmutableList.of(new OrderByColumnSpec(
                                    "_d0",
                                    OrderByColumnSpec.Direction.ASCENDING,
                                    StringComparators.LEXICOGRAPHIC
                                )),
                                2
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        NullHandling.replaceWithDefault() ?
        ImmutableList.of(
            new Object[]{"1", 1L},
            new Object[]{"2", 1L}
        ) :
        ImmutableList.of(
            new Object[]{"1", 2L},
            new Object[]{"2", 2L}
        )
    );
  }

  @Test
  public void testCountDistinctArithmetic() throws Exception
  {
    // Cannot vectorize due to "cardinality" aggregator.
    cannotVectorize();

    testQuery(
        "SELECT\n"
        + "  SUM(cnt),\n"
        + "  COUNT(DISTINCT dim2),\n"
        + "  CAST(COUNT(DISTINCT dim2) AS FLOAT),\n"
        + "  SUM(cnt) / COUNT(DISTINCT dim2),\n"
        + "  SUM(cnt) / COUNT(DISTINCT dim2) + 3,\n"
        + "  CAST(SUM(cnt) AS FLOAT) / CAST(COUNT(DISTINCT dim2) AS FLOAT) + 3\n"
        + "FROM druid.foo",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .aggregators(
                      aggregators(
                          new LongSumAggregatorFactory("a0", "cnt"),
                          new CardinalityAggregatorFactory(
                              "a1",
                              null,
                              dimensions(new DefaultDimensionSpec("dim2", null)),
                              false,
                              true
                          )
                      )
                  )
                  .postAggregators(
                      expressionPostAgg("p0", "CAST(\"a1\", 'DOUBLE')"),
                      expressionPostAgg("p1", "(\"a0\" / \"a1\")"),
                      expressionPostAgg("p2", "((\"a0\" / \"a1\") + 3)"),
                      expressionPostAgg("p3", "((CAST(\"a0\", 'DOUBLE') / CAST(\"a1\", 'DOUBLE')) + 3)")
                  )
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{6L, 3L, 3.0f, 2L, 5L, 5.0f}
        )
    );
  }

  @Test
  public void testCountDistinctOfSubstring() throws Exception
  {
    // Cannot vectorize due to "cardinality" aggregator.
    cannotVectorize();

    testQuery(
        "SELECT COUNT(DISTINCT SUBSTRING(dim1, 1, 1)) FROM druid.foo WHERE dim1 <> ''",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .filters(not(selector("dim1", "", null)))
                  .granularity(Granularities.ALL)
                  .aggregators(
                      aggregators(
                          new CardinalityAggregatorFactory(
                              "a0",
                              null,
                              dimensions(
                                  new ExtractionDimensionSpec(
                                      "dim1",
                                      null,
                                      new SubstringDimExtractionFn(0, 1)
                                  )
                              ),
                              false,
                              true
                          )
                      )
                  )
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{4L}
        )
    );
  }

  @Test
  public void testCountDistinctOfTrim() throws Exception
  {
    // Test a couple different syntax variants of TRIM.

    // Cannot vectorize due to virtual columns.
    cannotVectorize();

    testQuery(
        "SELECT COUNT(DISTINCT TRIM(BOTH ' ' FROM dim1)) FROM druid.foo WHERE TRIM(dim1) <> ''",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .virtualColumns(expressionVirtualColumn("v0", "trim(\"dim1\",' ')", ValueType.STRING))
                  .filters(not(selector("v0", NullHandling.emptyToNullIfNeeded(""), null)))
                  .aggregators(
                      aggregators(
                          new CardinalityAggregatorFactory(
                              "a0",
                              null,
                              dimensions(new DefaultDimensionSpec("v0", "v0", ValueType.STRING)),
                              false,
                              true
                          )
                      )
                  )
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{5L}
        )
    );
  }

  @Test
  public void testSillyQuarters() throws Exception
  {
    // Like FLOOR(__time TO QUARTER) but silly.

    // Cannot vectorize due to virtual columns.
    cannotVectorize();

    testQuery(
        "SELECT CAST((EXTRACT(MONTH FROM __time) - 1 ) / 3 + 1 AS INTEGER) AS quarter, COUNT(*)\n"
        + "FROM foo\n"
        + "GROUP BY CAST((EXTRACT(MONTH FROM __time) - 1 ) / 3 + 1 AS INTEGER)",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(expressionVirtualColumn(
                            "v0",
                            "(((timestamp_extract(\"__time\",'MONTH','UTC') - 1) / 3) + 1)",
                            ValueType.LONG
                        ))
                        .setDimensions(dimensions(new DefaultDimensionSpec("v0", "v0", ValueType.LONG)))
                        .setAggregatorSpecs(aggregators(new CountAggregatorFactory("a0")))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{1, 6L}
        )
    );
  }

  @Test
  public void testRegexpExtract() throws Exception
  {
    // Cannot vectorize due to extractionFn in dimension spec.
    cannotVectorize();

    String nullValue = NullHandling.replaceWithDefault() ? "" : null;
    testQuery(
        "SELECT DISTINCT\n"
        + "  REGEXP_EXTRACT(dim1, '^.'),\n"
        + "  REGEXP_EXTRACT(dim1, '^(.)', 1)\n"
        + "FROM foo\n"
        + "WHERE REGEXP_EXTRACT(dim1, '^(.)', 1) <> 'x'",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimFilter(
                            not(selector(
                                "dim1",
                                "x",
                                new RegexDimExtractionFn("^(.)", 1, true, null)
                            ))
                        )
                        .setDimensions(
                            dimensions(
                                new ExtractionDimensionSpec(
                                    "dim1",
                                    "d0",
                                    new RegexDimExtractionFn("^.", 0, true, null)
                                ),
                                new ExtractionDimensionSpec(
                                    "dim1",
                                    "d1",
                                    new RegexDimExtractionFn("^(.)", 1, true, null)
                                )
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{nullValue, nullValue},
            new Object[]{"1", "1"},
            new Object[]{"2", "2"},
            new Object[]{"a", "a"},
            new Object[]{"d", "d"}
        )
    );
  }

  @Test
  public void testGroupBySortPushDown() throws Exception
  {
    String nullValue = NullHandling.replaceWithDefault() ? "" : null;
    testQuery(
        "SELECT dim2, dim1, SUM(cnt) FROM druid.foo GROUP BY dim2, dim1 ORDER BY dim1 LIMIT 4",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("dim2", "d0"),
                                new DefaultDimensionSpec("dim1", "d1")
                            )
                        )
                        .setAggregatorSpecs(
                            aggregators(
                                new LongSumAggregatorFactory("a0", "cnt")
                            )
                        )
                        .setLimitSpec(
                            new DefaultLimitSpec(
                                ImmutableList.of(
                                    new OrderByColumnSpec("d1", OrderByColumnSpec.Direction.ASCENDING)
                                ),
                                4
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{"a", "", 1L},
            new Object[]{"a", "1", 1L},
            new Object[]{nullValue, "10.1", 1L},
            new Object[]{"", "2", 1L}
        )
    );
  }

  @Test
  public void testGroupByLimitPushDownWithHavingOnLong() throws Exception
  {
    String nullValue = NullHandling.replaceWithDefault() ? "" : null;
    testQuery(
        "SELECT dim1, dim2, SUM(cnt) AS thecnt "
        + "FROM druid.foo "
        + "group by dim1, dim2 "
        + "having SUM(cnt) = 1 "
        + "order by dim2 "
        + "limit 4",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("dim1", "d0"),
                                new DefaultDimensionSpec("dim2", "d1")
                            )
                        )
                        .setAggregatorSpecs(
                            aggregators(
                                new LongSumAggregatorFactory("a0", "cnt")
                            )
                        )
                        .setLimitSpec(
                            new DefaultLimitSpec(
                                ImmutableList.of(
                                    new OrderByColumnSpec("d1", OrderByColumnSpec.Direction.ASCENDING)
                                ),
                                4
                            )
                        )
                        .setHavingSpec(having(selector("a0", "1", null)))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        NullHandling.replaceWithDefault() ?
        ImmutableList.of(
            new Object[]{"10.1", "", 1L},
            new Object[]{"2", "", 1L},
            new Object[]{"abc", "", 1L},
            new Object[]{"", "a", 1L}
        ) :
        ImmutableList.of(
            new Object[]{"10.1", null, 1L},
            new Object[]{"abc", null, 1L},
            new Object[]{"2", "", 1L},
            new Object[]{"", "a", 1L}
        )
    );
  }

  @Test
  public void testFilterOnTimeFloor() throws Exception
  {
    testQuery(
        "SELECT COUNT(*) FROM druid.foo\n"
        + "WHERE\n"
        + "FLOOR(__time TO MONTH) = TIMESTAMP '2000-01-01 00:00:00'\n"
        + "OR FLOOR(__time TO MONTH) = TIMESTAMP '2000-02-01 00:00:00'",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Intervals.of("2000/P2M")))
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{3L}
        )
    );
  }

  @Test
  public void testGroupAndFilterOnTimeFloorWithTimeZone() throws Exception
  {
    testQuery(
        "SELECT TIME_FLOOR(__time, 'P1M', NULL, 'America/Los_Angeles'), COUNT(*)\n"
        + "FROM druid.foo\n"
        + "WHERE\n"
        + "TIME_FLOOR(__time, 'P1M', NULL, 'America/Los_Angeles') = "
        + "  TIME_PARSE('2000-01-01 00:00:00', NULL, 'America/Los_Angeles')\n"
        + "OR TIME_FLOOR(__time, 'P1M', NULL, 'America/Los_Angeles') = "
        + "  TIME_PARSE('2000-02-01 00:00:00', NULL, 'America/Los_Angeles')\n"
        + "GROUP BY 1",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Intervals.of("2000-01-01T00-08:00/2000-03-01T00-08:00")))
                  .granularity(new PeriodGranularity(Period.months(1), null, DateTimes.inferTzFromString(LOS_ANGELES)))
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{
                Calcites.jodaToCalciteTimestamp(
                    new DateTime("2000-01-01", DateTimes.inferTzFromString(LOS_ANGELES)),
                    DateTimeZone.UTC
                ),
                2L
            }
        )
    );
  }

  @Test
  public void testFilterOnCurrentTimestampWithIntervalArithmetic() throws Exception
  {
    testQuery(
        "SELECT COUNT(*) FROM druid.foo\n"
        + "WHERE\n"
        + "  __time >= CURRENT_TIMESTAMP + INTERVAL '01:02' HOUR TO MINUTE\n"
        + "  AND __time < TIMESTAMP '2003-02-02 01:00:00' - INTERVAL '1 1' DAY TO HOUR - INTERVAL '1-1' YEAR TO MONTH",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Intervals.of("2000-01-01T01:02/2002")))
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{5L}
        )
    );
  }

  @Test
  public void testSelectCurrentTimeAndDateLosAngeles() throws Exception
  {
    testQuery(
        PLANNER_CONFIG_DEFAULT,
        QUERY_CONTEXT_LOS_ANGELES,
        "SELECT CURRENT_TIMESTAMP, CURRENT_DATE, CURRENT_DATE + INTERVAL '1' DAY",
        CalciteTests.REGULAR_USER_AUTH_RESULT,
        ImmutableList.of(),
        ImmutableList.of(
            new Object[]{timestamp("2000-01-01T00Z", LOS_ANGELES), day("1999-12-31"), day("2000-01-01")}
        )
    );
  }

  @Test
  public void testFilterOnCurrentTimestampLosAngeles() throws Exception
  {
    testQuery(
        PLANNER_CONFIG_DEFAULT,
        QUERY_CONTEXT_LOS_ANGELES,
        "SELECT COUNT(*) FROM druid.foo\n"
        + "WHERE __time >= CURRENT_TIMESTAMP + INTERVAL '1' DAY AND __time < TIMESTAMP '2002-01-01 00:00:00'",
        CalciteTests.REGULAR_USER_AUTH_RESULT,
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Intervals.of("2000-01-02T00Z/2002-01-01T08Z")))
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_LOS_ANGELES)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{5L}
        )
    );
  }

  @Test
  public void testFilterOnCurrentTimestampOnView() throws Exception
  {
    testQuery(
        "SELECT * FROM bview",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Intervals.of("2000-01-02/2002")))
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{5L}
        )
    );
  }

  @Test
  public void testFilterOnCurrentTimestampLosAngelesOnView() throws Exception
  {
    // Tests that query context still applies to view SQL; note the result is different from
    // "testFilterOnCurrentTimestampOnView" above.

    testQuery(
        PLANNER_CONFIG_DEFAULT,
        QUERY_CONTEXT_LOS_ANGELES,
        "SELECT * FROM bview",
        CalciteTests.REGULAR_USER_AUTH_RESULT,
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Intervals.of("2000-01-02T00Z/2002-01-01T08Z")))
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_LOS_ANGELES)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{5L}
        )
    );
  }

  @Test
  public void testFilterOnNotTimeFloor() throws Exception
  {
    testQuery(
        "SELECT COUNT(*) FROM druid.foo\n"
        + "WHERE\n"
        + "FLOOR(__time TO MONTH) <> TIMESTAMP '2001-01-01 00:00:00'",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(
                      new Interval(DateTimes.MIN, DateTimes.of("2001-01-01")),
                      new Interval(DateTimes.of("2001-02-01"), DateTimes.MAX)
                  ))
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{3L}
        )
    );
  }

  @Test
  public void testFilterOnTimeFloorComparison() throws Exception
  {
    testQuery(
        "SELECT COUNT(*) FROM druid.foo\n"
        + "WHERE\n"
        + "FLOOR(__time TO MONTH) < TIMESTAMP '2000-02-01 00:00:00'",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(new Interval(DateTimes.MIN, DateTimes.of("2000-02-01"))))
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{3L}
        )
    );
  }

  @Test
  public void testFilterOnTimeFloorComparisonMisaligned() throws Exception
  {
    testQuery(
        "SELECT COUNT(*) FROM druid.foo\n"
        + "WHERE\n"
        + "FLOOR(__time TO MONTH) < TIMESTAMP '2000-02-01 00:00:01'",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(new Interval(DateTimes.MIN, DateTimes.of("2000-03-01"))))
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{3L}
        )
    );
  }

  @Test
  public void testFilterOnTimeExtract() throws Exception
  {
    // Cannot vectorize due to expression filter.
    cannotVectorize();

    testQuery(
        "SELECT COUNT(*) FROM druid.foo\n"
        + "WHERE EXTRACT(YEAR FROM __time) = 2000\n"
        + "AND EXTRACT(MONTH FROM __time) = 1",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .virtualColumns(
                      expressionVirtualColumn("v0", "timestamp_extract(\"__time\",'YEAR','UTC')", ValueType.LONG),
                      expressionVirtualColumn("v1", "timestamp_extract(\"__time\",'MONTH','UTC')", ValueType.LONG)
                  )
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .filters(
                      and(
                          selector("v0", "2000", null),
                          selector("v1", "1", null)
                      )
                  )
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{3L}
        )
    );
  }

  @Test
  public void testFilterOnTimeExtractWithMultipleDays() throws Exception
  {
    // Cannot vectorize due to expression filters.
    cannotVectorize();

    testQuery(
        "SELECT COUNT(*) FROM druid.foo\n"
        + "WHERE EXTRACT(YEAR FROM __time) = 2000\n"
        + "AND EXTRACT(DAY FROM __time) IN (2, 3, 5)",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .virtualColumns(
                      expressionVirtualColumn(
                          "v0",
                          "timestamp_extract(\"__time\",'YEAR','UTC')",
                          ValueType.LONG
                      ),
                      expressionVirtualColumn(
                          "v1",
                          "timestamp_extract(\"__time\",'DAY','UTC')",
                          ValueType.LONG
                      )
                  )
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .filters(
                      and(
                          selector("v0", "2000", null),
                          in("v1", ImmutableList.of("2", "3", "5"), null)
                      )
                  )
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{2L}
        )
    );
  }

  @Test
  public void testFilterOnTimeExtractWithVariousTimeUnits() throws Exception
  {
    // Cannot vectorize due to virtual columns.
    cannotVectorize();

    testQuery(
        "SELECT COUNT(*) FROM druid.foo4\n"
          + "WHERE EXTRACT(YEAR FROM __time) = 2000\n"
          + "AND EXTRACT(MICROSECOND FROM __time) = 946723\n"
          + "AND EXTRACT(MILLISECOND FROM __time) = 695\n"
          + "AND EXTRACT(ISODOW FROM __time) = 6\n"
          + "AND EXTRACT(ISOYEAR FROM __time) = 2000\n"
          + "AND EXTRACT(DECADE FROM __time) = 200\n"
          + "AND EXTRACT(CENTURY FROM __time) = 21\n"
          + "AND EXTRACT(MILLENNIUM FROM __time) = 2\n",

        TIMESERIES_CONTEXT_DEFAULT,
        ImmutableList.of(
        Druids.newTimeseriesQueryBuilder()
          .dataSource(CalciteTests.DATASOURCE4)
          .intervals(querySegmentSpec(Filtration.eternity()))
          .granularity(Granularities.ALL)
          .virtualColumns(
            expressionVirtualColumn("v0", "timestamp_extract(\"__time\",'YEAR','UTC')", ValueType.LONG),
            expressionVirtualColumn("v1", "timestamp_extract(\"__time\",'MICROSECOND','UTC')", ValueType.LONG),
            expressionVirtualColumn("v2", "timestamp_extract(\"__time\",'MILLISECOND','UTC')", ValueType.LONG),
            expressionVirtualColumn("v3", "timestamp_extract(\"__time\",'ISODOW','UTC')", ValueType.LONG),
            expressionVirtualColumn("v4", "timestamp_extract(\"__time\",'ISOYEAR','UTC')", ValueType.LONG),
            expressionVirtualColumn("v5", "timestamp_extract(\"__time\",'DECADE','UTC')", ValueType.LONG),
            expressionVirtualColumn("v6", "timestamp_extract(\"__time\",'CENTURY','UTC')", ValueType.LONG),
            expressionVirtualColumn("v7", "timestamp_extract(\"__time\",'MILLENNIUM','UTC')", ValueType.LONG)
            )
          .aggregators(aggregators(new CountAggregatorFactory("a0")))
          .filters(
            and(
              selector("v0", "2000", null),
              selector("v1", "946723", null),
              selector("v2", "695", null),
              selector("v3", "6", null),
              selector("v4", "2000", null),
              selector("v5", "200", null),
              selector("v6", "21", null),
              selector("v7", "2", null)
            )
          )
          .context(TIMESERIES_CONTEXT_DEFAULT)
          .build()
      ),
        ImmutableList.of(
        new Object[]{1L}
      )
    );
  }

  @Test
  public void testFilterOnTimeFloorMisaligned() throws Exception
  {
    testQuery(
        "SELECT COUNT(*) FROM druid.foo "
        + "WHERE floor(__time TO month) = TIMESTAMP '2000-01-01 00:00:01'",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec())
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of()
    );
  }

  @Test
  public void testGroupByFloor() throws Exception
  {
    // Cannot vectorize due to virtual columns.
    cannotVectorize();

    testQuery(
        PLANNER_CONFIG_NO_SUBQUERIES, // Sanity check; this simple query should work with subqueries disabled.
        "SELECT floor(CAST(dim1 AS float)), COUNT(*) FROM druid.foo GROUP BY floor(CAST(dim1 AS float))",
        CalciteTests.REGULAR_USER_AUTH_RESULT,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(
                            expressionVirtualColumn("v0", "floor(CAST(\"dim1\", 'DOUBLE'))", ValueType.FLOAT)
                        )
                        .setDimensions(dimensions(new DefaultDimensionSpec("v0", "v0", ValueType.FLOAT)))
                        .setAggregatorSpecs(aggregators(new CountAggregatorFactory("a0")))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{NullHandling.defaultFloatValue(), 3L},
            new Object[]{1.0f, 1L},
            new Object[]{2.0f, 1L},
            new Object[]{10.0f, 1L}
        )
    );
  }

  @Test
  public void testGroupByFloorWithOrderBy() throws Exception
  {
    // Cannot vectorize due to virtual columns.
    cannotVectorize();

    testQuery(
        "SELECT floor(CAST(dim1 AS float)) AS fl, COUNT(*) FROM druid.foo GROUP BY floor(CAST(dim1 AS float)) ORDER BY fl DESC",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(
                            expressionVirtualColumn(
                                "v0",
                                "floor(CAST(\"dim1\", 'DOUBLE'))",
                                ValueType.FLOAT
                            )
                        )
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec(
                                    "v0",
                                    "v0",
                                    ValueType.FLOAT
                                )
                            )
                        )
                        .setAggregatorSpecs(aggregators(new CountAggregatorFactory("a0")))
                        .setLimitSpec(
                            new DefaultLimitSpec(
                                ImmutableList.of(
                                    new OrderByColumnSpec(
                                        "v0",
                                        OrderByColumnSpec.Direction.DESCENDING,
                                        StringComparators.NUMERIC
                                    )
                                ),
                                Integer.MAX_VALUE
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{10.0f, 1L},
            new Object[]{2.0f, 1L},
            new Object[]{1.0f, 1L},
            new Object[]{NullHandling.defaultFloatValue(), 3L}
        )
    );
  }

  @Test
  public void testGroupByFloorTimeAndOneOtherDimensionWithOrderBy() throws Exception
  {
    // Cannot vectorize due to virtual columns.
    cannotVectorize();

    testQuery(
        "SELECT floor(__time TO year), dim2, COUNT(*)"
        + " FROM druid.foo"
        + " GROUP BY floor(__time TO year), dim2"
        + " ORDER BY floor(__time TO year), dim2, COUNT(*) DESC",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(
                            expressionVirtualColumn(
                                "v0",
                                "timestamp_floor(\"__time\",'P1Y',null,'UTC')",
                                ValueType.LONG
                            )
                        )
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("v0", "v0", ValueType.LONG),
                                new DefaultDimensionSpec("dim2", "d0")
                            )
                        )
                        .setAggregatorSpecs(
                            aggregators(
                                new CountAggregatorFactory("a0")
                            )
                        )
                        .setLimitSpec(
                            new DefaultLimitSpec(
                                ImmutableList.of(
                                    new OrderByColumnSpec(
                                        "v0",
                                        OrderByColumnSpec.Direction.ASCENDING,
                                        StringComparators.NUMERIC
                                    ),
                                    new OrderByColumnSpec(
                                        "d0",
                                        OrderByColumnSpec.Direction.ASCENDING,
                                        StringComparators.LEXICOGRAPHIC
                                    ),
                                    new OrderByColumnSpec(
                                        "a0",
                                        OrderByColumnSpec.Direction.DESCENDING,
                                        StringComparators.NUMERIC
                                    )
                                ),
                                Integer.MAX_VALUE
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        NullHandling.replaceWithDefault() ?
        ImmutableList.of(
            new Object[]{timestamp("2000"), "", 2L},
            new Object[]{timestamp("2000"), "a", 1L},
            new Object[]{timestamp("2001"), "", 1L},
            new Object[]{timestamp("2001"), "a", 1L},
            new Object[]{timestamp("2001"), "abc", 1L}
        ) :
        ImmutableList.of(
            new Object[]{timestamp("2000"), null, 1L},
            new Object[]{timestamp("2000"), "", 1L},
            new Object[]{timestamp("2000"), "a", 1L},
            new Object[]{timestamp("2001"), null, 1L},
            new Object[]{timestamp("2001"), "a", 1L},
            new Object[]{timestamp("2001"), "abc", 1L}
        )
    );
  }

  @Test
  public void testGroupByStringLength() throws Exception
  {
    // Cannot vectorize due to virtual columns.
    cannotVectorize();

    testQuery(
        "SELECT CHARACTER_LENGTH(dim1), COUNT(*) FROM druid.foo GROUP BY CHARACTER_LENGTH(dim1)",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(expressionVirtualColumn("v0", "strlen(\"dim1\")", ValueType.LONG))
                        .setDimensions(dimensions(new DefaultDimensionSpec("v0", "v0", ValueType.LONG)))
                        .setAggregatorSpecs(aggregators(new CountAggregatorFactory("a0")))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{0, 1L},
            new Object[]{1, 2L},
            new Object[]{3, 2L},
            new Object[]{4, 1L}
        )
    );
  }

  @Test
  public void testFilterAndGroupByLookup() throws Exception
  {
    // Cannot vectorize due to extraction dimension specs.
    cannotVectorize();

    String nullValue = NullHandling.replaceWithDefault() ? "" : null;
    final RegisteredLookupExtractionFn extractionFn = new RegisteredLookupExtractionFn(
        null,
        "lookyloo",
        false,
        null,
        null,
        true
    );

    testQuery(
        "SELECT LOOKUP(dim1, 'lookyloo'), COUNT(*) FROM foo\n"
        + "WHERE LOOKUP(dim1, 'lookyloo') <> 'xxx'\n"
        + "GROUP BY LOOKUP(dim1, 'lookyloo')",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimFilter(
                            not(selector(
                                "dim1",
                                "xxx",
                                extractionFn
                            ))
                        )
                        .setDimensions(
                            dimensions(
                                new ExtractionDimensionSpec(
                                    "dim1",
                                    "d0",
                                    ValueType.STRING,
                                    extractionFn
                                )
                            )
                        )
                        .setAggregatorSpecs(
                            aggregators(
                                new CountAggregatorFactory("a0")
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{nullValue, 5L},
            new Object[]{"xabc", 1L}
        )
    );
  }

  @Test
  public void testCountDistinctOfLookup() throws Exception
  {
    // Cannot vectorize due to "cardinality" aggregator.
    cannotVectorize();

    final RegisteredLookupExtractionFn extractionFn = new RegisteredLookupExtractionFn(
        null,
        "lookyloo",
        false,
        null,
        null,
        true
    );

    testQuery(
        "SELECT COUNT(DISTINCT LOOKUP(dim1, 'lookyloo')) FROM foo",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(
                      new CardinalityAggregatorFactory(
                          "a0",
                          null,
                          ImmutableList.of(new ExtractionDimensionSpec("dim1", null, extractionFn)),
                          false,
                          true
                      )
                  ))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{NullHandling.replaceWithDefault() ? 2L : 1L}
        )
    );
  }

  @Test
  public void testTimeseries() throws Exception
  {
    testQuery(
        "SELECT SUM(cnt), gran FROM (\n"
        + "  SELECT floor(__time TO month) AS gran,\n"
        + "  cnt FROM druid.foo\n"
        + ") AS x\n"
        + "GROUP BY gran\n"
        + "ORDER BY gran",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.MONTH)
                  .aggregators(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{3L, timestamp("2000-01-01")},
            new Object[]{3L, timestamp("2001-01-01")}
        )
    );
  }

  @Test
  public void testFilteredTimeAggregators() throws Exception
  {
    testQuery(
        "SELECT\n"
        + "  SUM(cnt) FILTER(WHERE __time >= TIMESTAMP '2000-01-01 00:00:00'\n"
        + "                    AND __time <  TIMESTAMP '2000-02-01 00:00:00'),\n"
        + "  SUM(cnt) FILTER(WHERE __time >= TIMESTAMP '2001-01-01 00:00:00'\n"
        + "                    AND __time <  TIMESTAMP '2001-02-01 00:00:00')\n"
        + "FROM foo\n"
        + "WHERE\n"
        + "  __time >= TIMESTAMP '2000-01-01 00:00:00'\n"
        + "  AND __time < TIMESTAMP '2001-02-01 00:00:00'",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Intervals.of("2000-01-01/2001-02-01")))
                  .granularity(Granularities.ALL)
                  .aggregators(aggregators(
                      new FilteredAggregatorFactory(
                          new LongSumAggregatorFactory("a0", "cnt"),
                          bound(
                              "__time",
                              String.valueOf(timestamp("2000-01-01")),
                              String.valueOf(timestamp("2000-02-01")),
                              false,
                              true,
                              null,
                              StringComparators.NUMERIC
                          )
                      ),
                      new FilteredAggregatorFactory(
                          new LongSumAggregatorFactory("a1", "cnt"),
                          bound(
                              "__time",
                              String.valueOf(timestamp("2001-01-01")),
                              String.valueOf(timestamp("2001-02-01")),
                              false,
                              true,
                              null,
                              StringComparators.NUMERIC
                          )
                      )
                  ))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{3L, 3L}
        )
    );
  }

  @Test
  public void testTimeseriesLosAngelesViaQueryContext() throws Exception
  {
    testQuery(
        PLANNER_CONFIG_DEFAULT,
        QUERY_CONTEXT_LOS_ANGELES,
        "SELECT SUM(cnt), gran FROM (\n"
        + "  SELECT FLOOR(__time TO MONTH) AS gran,\n"
        + "  cnt FROM druid.foo\n"
        + ") AS x\n"
        + "GROUP BY gran\n"
        + "ORDER BY gran",
        CalciteTests.REGULAR_USER_AUTH_RESULT,
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(new PeriodGranularity(Period.months(1), null, DateTimes.inferTzFromString(LOS_ANGELES)))
                  .aggregators(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                  .context(TIMESERIES_CONTEXT_LOS_ANGELES)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{1L, timestamp("1999-12-01", LOS_ANGELES)},
            new Object[]{2L, timestamp("2000-01-01", LOS_ANGELES)},
            new Object[]{1L, timestamp("2000-12-01", LOS_ANGELES)},
            new Object[]{2L, timestamp("2001-01-01", LOS_ANGELES)}
        )
    );
  }

  @Test
  public void testTimeseriesLosAngelesViaPlannerConfig() throws Exception
  {
    testQuery(
        PLANNER_CONFIG_LOS_ANGELES,
        QUERY_CONTEXT_DEFAULT,
        "SELECT SUM(cnt), gran FROM (\n"
        + "  SELECT\n"
        + "    FLOOR(__time TO MONTH) AS gran,\n"
        + "    cnt\n"
        + "  FROM druid.foo\n"
        + "  WHERE __time >= TIME_PARSE('1999-12-01 00:00:00') AND __time < TIME_PARSE('2002-01-01 00:00:00')\n"
        + ") AS x\n"
        + "GROUP BY gran\n"
        + "ORDER BY gran",
        CalciteTests.REGULAR_USER_AUTH_RESULT,
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Intervals.of("1999-12-01T00-08:00/2002-01-01T00-08:00")))
                  .granularity(new PeriodGranularity(Period.months(1), null, DateTimes.inferTzFromString(LOS_ANGELES)))
                  .aggregators(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{1L, timestamp("1999-12-01", LOS_ANGELES)},
            new Object[]{2L, timestamp("2000-01-01", LOS_ANGELES)},
            new Object[]{1L, timestamp("2000-12-01", LOS_ANGELES)},
            new Object[]{2L, timestamp("2001-01-01", LOS_ANGELES)}
        )
    );
  }

  @Test
  public void testTimeseriesUsingTimeFloor() throws Exception
  {
    testQuery(
        "SELECT SUM(cnt), gran FROM (\n"
        + "  SELECT TIME_FLOOR(__time, 'P1M') AS gran,\n"
        + "  cnt FROM druid.foo\n"
        + ") AS x\n"
        + "GROUP BY gran\n"
        + "ORDER BY gran",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.MONTH)
                  .aggregators(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{3L, timestamp("2000-01-01")},
            new Object[]{3L, timestamp("2001-01-01")}
        )
    );
  }

  @Test
  public void testTimeseriesUsingTimeFloorWithTimeShift() throws Exception
  {
    // Cannot vectorize due to virtual columns.
    cannotVectorize();

    testQuery(
        "SELECT SUM(cnt), gran FROM (\n"
        + "  SELECT TIME_FLOOR(TIME_SHIFT(__time, 'P1D', -1), 'P1M') AS gran,\n"
        + "  cnt FROM druid.foo\n"
        + ") AS x\n"
        + "GROUP BY gran\n"
        + "ORDER BY gran",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(
                            expressionVirtualColumn(
                                "v0",
                                "timestamp_floor(timestamp_shift(\"__time\",'P1D',-1,'UTC'),'P1M',null,'UTC')",
                                ValueType.LONG
                            )
                        )
                        .setDimensions(dimensions(new DefaultDimensionSpec("v0", "v0", ValueType.LONG)))
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(
                            new DefaultLimitSpec(
                                ImmutableList.of(
                                    new OrderByColumnSpec(
                                        "v0",
                                        OrderByColumnSpec.Direction.ASCENDING,
                                        StringComparators.NUMERIC
                                    )
                                ),
                                Integer.MAX_VALUE
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{1L, timestamp("1999-12-01")},
            new Object[]{2L, timestamp("2000-01-01")},
            new Object[]{1L, timestamp("2000-12-01")},
            new Object[]{2L, timestamp("2001-01-01")}
        )
    );
  }

  @Test
  public void testTimeseriesUsingTimeFloorWithTimestampAdd() throws Exception
  {
    // Cannot vectorize due to virtual columns.
    cannotVectorize();

    testQuery(
        "SELECT SUM(cnt), gran FROM (\n"
        + "  SELECT TIME_FLOOR(TIMESTAMPADD(DAY, -1, __time), 'P1M') AS gran,\n"
        + "  cnt FROM druid.foo\n"
        + ") AS x\n"
        + "GROUP BY gran\n"
        + "ORDER BY gran",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(
                            expressionVirtualColumn(
                                "v0",
                                "timestamp_floor((\"__time\" + -86400000),'P1M',null,'UTC')",
                                ValueType.LONG
                            )
                        )
                        .setDimensions(dimensions(new DefaultDimensionSpec("v0", "v0", ValueType.LONG)))
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(
                            new DefaultLimitSpec(
                                ImmutableList.of(
                                    new OrderByColumnSpec(
                                        "v0",
                                        OrderByColumnSpec.Direction.ASCENDING,
                                        StringComparators.NUMERIC
                                    )
                                ),
                                Integer.MAX_VALUE
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{1L, timestamp("1999-12-01")},
            new Object[]{2L, timestamp("2000-01-01")},
            new Object[]{1L, timestamp("2000-12-01")},
            new Object[]{2L, timestamp("2001-01-01")}
        )
    );
  }

  @Test
  public void testTimeseriesUsingTimeFloorWithOrigin() throws Exception
  {
    testQuery(
        "SELECT SUM(cnt), gran FROM (\n"
        + "  SELECT TIME_FLOOR(__time, 'P1M', TIMESTAMP '1970-01-01 01:02:03') AS gran,\n"
        + "  cnt FROM druid.foo\n"
        + ") AS x\n"
        + "GROUP BY gran\n"
        + "ORDER BY gran",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(
                      new PeriodGranularity(
                          Period.months(1),
                          DateTimes.of("1970-01-01T01:02:03"),
                          DateTimeZone.UTC
                      )
                  )
                  .aggregators(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{1L, timestamp("1999-12-01T01:02:03")},
            new Object[]{2L, timestamp("2000-01-01T01:02:03")},
            new Object[]{1L, timestamp("2000-12-01T01:02:03")},
            new Object[]{2L, timestamp("2001-01-01T01:02:03")}
        )
    );
  }

  @Test
  public void testTimeseriesLosAngelesUsingTimeFloorConnectionUtc() throws Exception
  {
    testQuery(
        "SELECT SUM(cnt), gran FROM (\n"
        + "  SELECT TIME_FLOOR(__time, 'P1M', CAST(NULL AS TIMESTAMP), 'America/Los_Angeles') AS gran,\n"
        + "  cnt FROM druid.foo\n"
        + ") AS x\n"
        + "GROUP BY gran\n"
        + "ORDER BY gran",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(new PeriodGranularity(Period.months(1), null, DateTimes.inferTzFromString(LOS_ANGELES)))
                  .aggregators(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{1L, timestamp("1999-12-01T08")},
            new Object[]{2L, timestamp("2000-01-01T08")},
            new Object[]{1L, timestamp("2000-12-01T08")},
            new Object[]{2L, timestamp("2001-01-01T08")}
        )
    );
  }

  @Test
  public void testTimeseriesLosAngelesUsingTimeFloorConnectionLosAngeles() throws Exception
  {
    testQuery(
        PLANNER_CONFIG_DEFAULT,
        QUERY_CONTEXT_LOS_ANGELES,
        "SELECT SUM(cnt), gran FROM (\n"
        + "  SELECT TIME_FLOOR(__time, 'P1M') AS gran,\n"
        + "  cnt FROM druid.foo\n"
        + ") AS x\n"
        + "GROUP BY gran\n"
        + "ORDER BY gran",
        CalciteTests.REGULAR_USER_AUTH_RESULT,
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(new PeriodGranularity(Period.months(1), null, DateTimes.inferTzFromString(LOS_ANGELES)))
                  .aggregators(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                  .context(TIMESERIES_CONTEXT_LOS_ANGELES)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{1L, timestamp("1999-12-01", LOS_ANGELES)},
            new Object[]{2L, timestamp("2000-01-01", LOS_ANGELES)},
            new Object[]{1L, timestamp("2000-12-01", LOS_ANGELES)},
            new Object[]{2L, timestamp("2001-01-01", LOS_ANGELES)}
        )
    );
  }

  @Test
  public void testTimeseriesDontSkipEmptyBuckets() throws Exception
  {
    // Tests that query context parameters are passed through to the underlying query engine.
    Long defaultVal = NullHandling.replaceWithDefault() ? 0L : null;
    testQuery(
        PLANNER_CONFIG_DEFAULT,
        QUERY_CONTEXT_DONT_SKIP_EMPTY_BUCKETS,
        "SELECT SUM(cnt), gran FROM (\n"
        + "  SELECT floor(__time TO HOUR) AS gran, cnt FROM druid.foo\n"
        + "  WHERE __time >= TIMESTAMP '2000-01-01 00:00:00' AND __time < TIMESTAMP '2000-01-02 00:00:00'\n"
        + ") AS x\n"
        + "GROUP BY gran\n"
        + "ORDER BY gran",
        CalciteTests.REGULAR_USER_AUTH_RESULT,
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Intervals.of("2000/2000-01-02")))
                  .granularity(new PeriodGranularity(Period.hours(1), null, DateTimeZone.UTC))
                  .aggregators(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                  .context(QUERY_CONTEXT_DONT_SKIP_EMPTY_BUCKETS)
                  .build()
        ),
        ImmutableList.<Object[]>builder()
            .add(new Object[]{1L, timestamp("2000-01-01")})
            .add(new Object[]{defaultVal, timestamp("2000-01-01T01")})
            .add(new Object[]{defaultVal, timestamp("2000-01-01T02")})
            .add(new Object[]{defaultVal, timestamp("2000-01-01T03")})
            .add(new Object[]{defaultVal, timestamp("2000-01-01T04")})
            .add(new Object[]{defaultVal, timestamp("2000-01-01T05")})
            .add(new Object[]{defaultVal, timestamp("2000-01-01T06")})
            .add(new Object[]{defaultVal, timestamp("2000-01-01T07")})
            .add(new Object[]{defaultVal, timestamp("2000-01-01T08")})
            .add(new Object[]{defaultVal, timestamp("2000-01-01T09")})
            .add(new Object[]{defaultVal, timestamp("2000-01-01T10")})
            .add(new Object[]{defaultVal, timestamp("2000-01-01T11")})
            .add(new Object[]{defaultVal, timestamp("2000-01-01T12")})
            .add(new Object[]{defaultVal, timestamp("2000-01-01T13")})
            .add(new Object[]{defaultVal, timestamp("2000-01-01T14")})
            .add(new Object[]{defaultVal, timestamp("2000-01-01T15")})
            .add(new Object[]{defaultVal, timestamp("2000-01-01T16")})
            .add(new Object[]{defaultVal, timestamp("2000-01-01T17")})
            .add(new Object[]{defaultVal, timestamp("2000-01-01T18")})
            .add(new Object[]{defaultVal, timestamp("2000-01-01T19")})
            .add(new Object[]{defaultVal, timestamp("2000-01-01T20")})
            .add(new Object[]{defaultVal, timestamp("2000-01-01T21")})
            .add(new Object[]{defaultVal, timestamp("2000-01-01T22")})
            .add(new Object[]{defaultVal, timestamp("2000-01-01T23")})
            .build()
    );
  }

  @Test
  public void testTimeseriesUsingCastAsDate() throws Exception
  {
    testQuery(
        "SELECT SUM(cnt), dt FROM (\n"
        + "  SELECT CAST(__time AS DATE) AS dt,\n"
        + "  cnt FROM druid.foo\n"
        + ") AS x\n"
        + "GROUP BY dt\n"
        + "ORDER BY dt",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(new PeriodGranularity(Period.days(1), null, DateTimeZone.UTC))
                  .aggregators(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{1L, day("2000-01-01")},
            new Object[]{1L, day("2000-01-02")},
            new Object[]{1L, day("2000-01-03")},
            new Object[]{1L, day("2001-01-01")},
            new Object[]{1L, day("2001-01-02")},
            new Object[]{1L, day("2001-01-03")}
        )
    );
  }

  @Test
  public void testTimeseriesUsingFloorPlusCastAsDate() throws Exception
  {
    testQuery(
        "SELECT SUM(cnt), dt FROM (\n"
        + "  SELECT CAST(FLOOR(__time TO QUARTER) AS DATE) AS dt,\n"
        + "  cnt FROM druid.foo\n"
        + ") AS x\n"
        + "GROUP BY dt\n"
        + "ORDER BY dt",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(new PeriodGranularity(Period.months(3), null, DateTimeZone.UTC))
                  .aggregators(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{3L, day("2000-01-01")},
            new Object[]{3L, day("2001-01-01")}
        )
    );
  }

  @Test
  public void testTimeseriesDescending() throws Exception
  {
    // Cannot vectorize due to descending order.
    cannotVectorize();

    testQuery(
        "SELECT gran, SUM(cnt) FROM (\n"
        + "  SELECT floor(__time TO month) AS gran,\n"
        + "  cnt FROM druid.foo\n"
        + ") AS x\n"
        + "GROUP BY gran\n"
        + "ORDER BY gran DESC",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.MONTH)
                  .aggregators(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                  .descending(true)
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{timestamp("2001-01-01"), 3L},
            new Object[]{timestamp("2000-01-01"), 3L}
        )
    );
  }

  @Test
  public void testGroupByExtractYear() throws Exception
  {
    // Cannot vectorize due to virtual columns.
    cannotVectorize();

    testQuery(
        "SELECT\n"
        + "  EXTRACT(YEAR FROM __time) AS \"year\",\n"
        + "  SUM(cnt)\n"
        + "FROM druid.foo\n"
        + "GROUP BY EXTRACT(YEAR FROM __time)\n"
        + "ORDER BY 1",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(
                            expressionVirtualColumn(
                                "v0",
                                "timestamp_extract(\"__time\",'YEAR','UTC')",
                                ValueType.LONG
                            )
                        )
                        .setDimensions(dimensions(new DefaultDimensionSpec("v0", "v0", ValueType.LONG)))
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(
                            new DefaultLimitSpec(
                                ImmutableList.of(
                                    new OrderByColumnSpec(
                                        "v0",
                                        OrderByColumnSpec.Direction.ASCENDING,
                                        StringComparators.NUMERIC
                                    )
                                ),
                                Integer.MAX_VALUE
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{2000L, 3L},
            new Object[]{2001L, 3L}
        )
    );
  }

  @Test
  public void testGroupByFormatYearAndMonth() throws Exception
  {
    // Cannot vectorize due to virtual columns.
    cannotVectorize();

    testQuery(
        "SELECT\n"
        + "  TIME_FORMAt(__time, 'yyyy MM') AS \"year\",\n"
        + "  SUM(cnt)\n"
        + "FROM druid.foo\n"
        + "GROUP BY TIME_FORMAt(__time, 'yyyy MM')\n"
        + "ORDER BY 1",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(
                            expressionVirtualColumn(
                                "v0",
                                "timestamp_format(\"__time\",'yyyy MM','UTC')",
                                ValueType.STRING
                            )
                        )
                        .setDimensions(dimensions(new DefaultDimensionSpec("v0", "v0", ValueType.STRING)))
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(
                            new DefaultLimitSpec(
                                ImmutableList.of(
                                    new OrderByColumnSpec(
                                        "v0",
                                        OrderByColumnSpec.Direction.ASCENDING,
                                        StringComparators.LEXICOGRAPHIC
                                    )
                                ),
                                Integer.MAX_VALUE
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{"2000 01", 3L},
            new Object[]{"2001 01", 3L}
        )
    );
  }

  @Test
  public void testGroupByExtractFloorTime() throws Exception
  {
    // Cannot vectorize due to virtual columns.
    cannotVectorize();

    testQuery(
        "SELECT\n"
        + "EXTRACT(YEAR FROM FLOOR(__time TO YEAR)) AS \"year\", SUM(cnt)\n"
        + "FROM druid.foo\n"
        + "GROUP BY EXTRACT(YEAR FROM FLOOR(__time TO YEAR))",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(
                            expressionVirtualColumn(
                                "v0",
                                "timestamp_extract(timestamp_floor(\"__time\",'P1Y',null,'UTC'),'YEAR','UTC')",
                                ValueType.LONG
                            )
                        )
                        .setDimensions(dimensions(new DefaultDimensionSpec("v0", "v0", ValueType.LONG)))
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{2000L, 3L},
            new Object[]{2001L, 3L}
        )
    );
  }

  @Test
  public void testGroupByExtractFloorTimeLosAngeles() throws Exception
  {
    // Cannot vectorize due to virtual columns.
    cannotVectorize();

    testQuery(
        PLANNER_CONFIG_DEFAULT,
        QUERY_CONTEXT_LOS_ANGELES,
        "SELECT\n"
        + "EXTRACT(YEAR FROM FLOOR(__time TO YEAR)) AS \"year\", SUM(cnt)\n"
        + "FROM druid.foo\n"
        + "GROUP BY EXTRACT(YEAR FROM FLOOR(__time TO YEAR))",
        CalciteTests.REGULAR_USER_AUTH_RESULT,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(
                            expressionVirtualColumn(
                                "v0",
                                "timestamp_extract(timestamp_floor(\"__time\",'P1Y',null,'America/Los_Angeles'),'YEAR','America/Los_Angeles')",
                                ValueType.LONG
                            )
                        )
                        .setDimensions(dimensions(new DefaultDimensionSpec("v0", "v0", ValueType.LONG)))
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setContext(QUERY_CONTEXT_LOS_ANGELES)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{1999L, 1L},
            new Object[]{2000L, 3L},
            new Object[]{2001L, 2L}
        )
    );
  }

  @Test
  public void testTimeseriesWithLimitNoTopN() throws Exception
  {
    testQuery(
        PLANNER_CONFIG_NO_TOPN,
        "SELECT gran, SUM(cnt)\n"
        + "FROM (\n"
        + "  SELECT floor(__time TO month) AS gran, cnt\n"
        + "  FROM druid.foo\n"
        + ") AS x\n"
        + "GROUP BY gran\n"
        + "ORDER BY gran\n"
        + "LIMIT 1",
        CalciteTests.REGULAR_USER_AUTH_RESULT,
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.MONTH)
                  .aggregators(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                  .limit(1)
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{timestamp("2000-01-01"), 3L}
        )
    );
  }

  @Test
  public void testTimeseriesWithLimit() throws Exception
  {
    testQuery(
        "SELECT gran, SUM(cnt)\n"
        + "FROM (\n"
        + "  SELECT floor(__time TO month) AS gran, cnt\n"
        + "  FROM druid.foo\n"
        + ") AS x\n"
        + "GROUP BY gran\n"
        + "LIMIT 1",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.MONTH)
                  .aggregators(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                  .limit(1)
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{timestamp("2000-01-01"), 3L}
        )
    );
  }

  @Test
  public void testTimeseriesWithOrderByAndLimit() throws Exception
  {
    testQuery(
        "SELECT gran, SUM(cnt)\n"
        + "FROM (\n"
        + "  SELECT floor(__time TO month) AS gran, cnt\n"
        + "  FROM druid.foo\n"
        + ") AS x\n"
        + "GROUP BY gran\n"
        + "ORDER BY gran\n"
        + "LIMIT 1",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.MONTH)
                  .aggregators(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                  .limit(1)
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{timestamp("2000-01-01"), 3L}
        )
    );
  }

  @Test
  public void testGroupByTimeAndOtherDimension() throws Exception
  {
    // Cannot vectorize due to virtual columns.
    cannotVectorize();

    testQuery(
        "SELECT dim2, gran, SUM(cnt)\n"
        + "FROM (SELECT FLOOR(__time TO MONTH) AS gran, dim2, cnt FROM druid.foo) AS x\n"
        + "GROUP BY dim2, gran\n"
        + "ORDER BY dim2, gran",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(
                            expressionVirtualColumn(
                                "v0",
                                "timestamp_floor(\"__time\",'P1M',null,'UTC')",
                                ValueType.LONG
                            )
                        )
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("dim2", "d0"),
                                new DefaultDimensionSpec("v0", "v0", ValueType.LONG)
                            )
                        )
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(
                            new DefaultLimitSpec(
                                ImmutableList.of(
                                    new OrderByColumnSpec("d0", OrderByColumnSpec.Direction.ASCENDING),
                                    new OrderByColumnSpec(
                                        "v0",
                                        OrderByColumnSpec.Direction.ASCENDING,
                                        StringComparators.NUMERIC
                                    )
                                ),
                                Integer.MAX_VALUE
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        NullHandling.replaceWithDefault() ?
        ImmutableList.of(
            new Object[]{"", timestamp("2000-01-01"), 2L},
            new Object[]{"", timestamp("2001-01-01"), 1L},
            new Object[]{"a", timestamp("2000-01-01"), 1L},
            new Object[]{"a", timestamp("2001-01-01"), 1L},
            new Object[]{"abc", timestamp("2001-01-01"), 1L}
        ) :
        ImmutableList.of(
            new Object[]{null, timestamp("2000-01-01"), 1L},
            new Object[]{null, timestamp("2001-01-01"), 1L},
            new Object[]{"", timestamp("2000-01-01"), 1L},
            new Object[]{"a", timestamp("2000-01-01"), 1L},
            new Object[]{"a", timestamp("2001-01-01"), 1L},
            new Object[]{"abc", timestamp("2001-01-01"), 1L}
        )
    );
  }

  @Test
  public void testUsingSubqueryAsPartOfAndFilter() throws Exception
  {
    testQuery(
        PLANNER_CONFIG_SINGLE_NESTING_ONLY, // Sanity check; this query should work with a single level of nesting.
        "SELECT dim1, dim2, COUNT(*) FROM druid.foo\n"
        + "WHERE dim2 IN (SELECT dim1 FROM druid.foo WHERE dim1 <> '')\n"
        + "AND dim1 <> 'xxx'\n"
        + "group by dim1, dim2 ORDER BY dim2",
        CalciteTests.REGULAR_USER_AUTH_RESULT,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimFilter(not(selector("dim1", "", null)))
                        .setDimensions(dimensions(new DefaultDimensionSpec("dim1", "d0")))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build(),
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimFilter(
                            and(
                                not(selector("dim1", "xxx", null)),
                                in("dim2", ImmutableList.of("1", "10.1", "2", "abc", "def"), null)
                            )
                        )
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("dim1", "d0"),
                                new DefaultDimensionSpec("dim2", "d1")
                            )
                        )
                        .setAggregatorSpecs(aggregators(new CountAggregatorFactory("a0")))
                        .setLimitSpec(
                            new DefaultLimitSpec(
                                ImmutableList.of(new OrderByColumnSpec("d1", OrderByColumnSpec.Direction.ASCENDING)),
                                Integer.MAX_VALUE
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{"def", "abc", 1L}
        )
    );
  }

  @Test
  public void testUsingSubqueryAsPartOfOrFilter()
  {
    // This query should ideally be plannable, but it's not. The "OR" means it isn't really
    // a semiJoin and so the filter condition doesn't get converted.

    final String theQuery = "SELECT dim1, dim2, COUNT(*) FROM druid.foo\n"
                            + "WHERE dim1 = 'xxx' OR dim2 IN (SELECT dim1 FROM druid.foo WHERE dim1 LIKE '%bc')\n"
                            + "group by dim1, dim2 ORDER BY dim2";

    assertQueryIsUnplannable(theQuery);
  }

  @Test
  public void testTimeExtractWithTooFewArguments() throws Exception
  {
    // Regression test for https://github.com/apache/incubator-druid/pull/7710.
    expectedException.expect(ValidationException.class);
    expectedException.expectCause(CoreMatchers.instanceOf(CalciteContextException.class));
    expectedException.expectCause(
        ThrowableMessageMatcher.hasMessage(
            CoreMatchers.containsString(
                "Invalid number of arguments to function 'TIME_EXTRACT'. Was expecting 2 arguments"
            )
        )
    );
    testQuery("SELECT TIME_EXTRACT(__time) FROM druid.foo", ImmutableList.of(), ImmutableList.of());
  }

  @Test
  public void testUsingSubqueryAsFilterForbiddenByConfig()
  {
    assertQueryIsUnplannable(
        PLANNER_CONFIG_NO_SUBQUERIES,
        "SELECT dim1, dim2, COUNT(*) FROM druid.foo "
        + "WHERE dim2 IN (SELECT dim1 FROM druid.foo WHERE dim1 <> '')"
        + "AND dim1 <> 'xxx'"
        + "group by dim1, dim2 ORDER BY dim2"
    );
  }

  @Test
  public void testUsingSubqueryAsFilterOnTwoColumns() throws Exception
  {
    testQuery(
        "SELECT __time, cnt, dim1, dim2 FROM druid.foo "
        + " WHERE (dim1, dim2) IN ("
        + "   SELECT dim1, dim2 FROM ("
        + "     SELECT dim1, dim2, COUNT(*)"
        + "     FROM druid.foo"
        + "     WHERE dim2 = 'abc'"
        + "     GROUP BY dim1, dim2"
        + "     HAVING COUNT(*) = 1"
        + "   )"
        + " )",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimFilter(selector("dim2", "abc", null))
                        .setDimensions(dimensions(
                            new DefaultDimensionSpec("dim1", "d0"),
                            new DefaultDimensionSpec("dim2", "d1")
                        ))
                        .setAggregatorSpecs(aggregators(new CountAggregatorFactory("a0")))
                        .setHavingSpec(having(selector("a0", "1", null)))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build(),
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE1)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .filters(or(
                    selector("dim1", "def", null),
                    and(
                        selector("dim1", "def", null),
                        selector("dim2", "abc", null)
                    )
                ))
                .columns("__time", "cnt", "dim1", "dim2")
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{timestamp("2001-01-02"), 1L, "def", "abc"}
        )
    );
  }

  @Test
  public void testUsingSubqueryAsFilterWithInnerSort() throws Exception
  {
    String nullValue = NullHandling.replaceWithDefault() ? "" : null;

    // Regression test for https://github.com/apache/incubator-druid/issues/4208
    testQuery(
        "SELECT dim1, dim2 FROM druid.foo\n"
        + " WHERE dim2 IN (\n"
        + "   SELECT dim2\n"
        + "   FROM druid.foo\n"
        + "   GROUP BY dim2\n"
        + "   ORDER BY dim2 DESC\n"
        + " )",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimensions(dimensions(new DefaultDimensionSpec("dim2", "d0")))
                        .setLimitSpec(
                            new DefaultLimitSpec(
                                ImmutableList.of(
                                    new OrderByColumnSpec(
                                        "d0",
                                        OrderByColumnSpec.Direction.DESCENDING,
                                        StringComparators.LEXICOGRAPHIC
                                    )
                                ),
                                Integer.MAX_VALUE
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build(),
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE1)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .filters(in("dim2", ImmutableList.of("", "a", "abc"), null))
                .columns("dim1", "dim2")
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        NullHandling.replaceWithDefault() ?
        ImmutableList.of(
            new Object[]{"", "a"},
            new Object[]{"10.1", nullValue},
            new Object[]{"2", ""},
            new Object[]{"1", "a"},
            new Object[]{"def", "abc"},
            new Object[]{"abc", nullValue}
        ) :
        ImmutableList.of(
            new Object[]{"", "a"},
            new Object[]{"2", ""},
            new Object[]{"1", "a"},
            new Object[]{"def", "abc"}
        )
    );
  }

  @Test
  public void testSemiJoinWithOuterTimeExtractScan() throws Exception
  {
    testQuery(
        "SELECT dim1, EXTRACT(MONTH FROM __time) FROM druid.foo\n"
        + " WHERE dim2 IN (\n"
        + "   SELECT dim2\n"
        + "   FROM druid.foo\n"
        + "   WHERE dim1 = 'def'\n"
        + " ) AND dim1 <> ''",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimensions(dimensions(new DefaultDimensionSpec("dim2", "d0")))
                        .setDimFilter(selector("dim1", "def", null))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build(),
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE1)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .virtualColumns(
                    expressionVirtualColumn("v0", "timestamp_extract(\"__time\",'MONTH','UTC')", ValueType.LONG)
                )
                .filters(
                    and(
                        not(selector("dim1", "", null)),
                        selector("dim2", "abc", null)
                    )
                )
                .columns("dim1", "v0")
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"def", 1L}
        )
    );
  }

  @Test
  public void testSemiJoinWithOuterTimeExtractAggregateWithOrderBy() throws Exception
  {
    // Cannot vectorize due to virtual columns.
    cannotVectorize();

    testQuery(
        "SELECT COUNT(DISTINCT dim1), EXTRACT(MONTH FROM __time) FROM druid.foo\n"
        + " WHERE dim2 IN (\n"
        + "   SELECT dim2\n"
        + "   FROM druid.foo\n"
        + "   WHERE dim1 = 'def'\n"
        + " ) AND dim1 <> ''"
        + "GROUP BY EXTRACT(MONTH FROM __time)\n"
        + "ORDER BY EXTRACT(MONTH FROM __time)",
        ImmutableList.of(
            GroupByQuery
                .builder()
                .setDataSource(CalciteTests.DATASOURCE1)
                .setInterval(querySegmentSpec(Filtration.eternity()))
                .setGranularity(Granularities.ALL)
                .setDimensions(dimensions(new DefaultDimensionSpec("dim2", "d0")))
                .setDimFilter(selector("dim1", "def", null))
                .setContext(QUERY_CONTEXT_DEFAULT)
                .build(),
            GroupByQuery
                .builder()
                .setDataSource(CalciteTests.DATASOURCE1)
                .setVirtualColumns(
                    expressionVirtualColumn("v0", "timestamp_extract(\"__time\",'MONTH','UTC')", ValueType.LONG)
                )
                .setDimFilter(
                    and(
                        not(selector("dim1", "", null)),
                        selector("dim2", "abc", null)
                    )
                )
                .setDimensions(dimensions(new DefaultDimensionSpec("v0", "v0", ValueType.LONG)))
                .setInterval(querySegmentSpec(Filtration.eternity()))
                .setGranularity(Granularities.ALL)
                .setAggregatorSpecs(
                    aggregators(
                        new CardinalityAggregatorFactory(
                            "a0",
                            null,
                            ImmutableList.of(
                                new DefaultDimensionSpec("dim1", "dim1", ValueType.STRING)
                            ),
                            false,
                            true
                        )
                    )
                )
                .setLimitSpec(
                    new DefaultLimitSpec(
                        ImmutableList.of(
                            new OrderByColumnSpec(
                                "v0",
                                OrderByColumnSpec.Direction.ASCENDING,
                                StringComparators.NUMERIC
                            )
                        ),
                        Integer.MAX_VALUE
                    )
                )
                .setContext(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{1L, 1L}
        )
    );
  }

  @Test
  public void testUsingSubqueryWithExtractionFns() throws Exception
  {
    // Cannot vectorize due to extraction dimension specs.
    cannotVectorize();

    testQuery(
        "SELECT dim2, COUNT(*) FROM druid.foo "
        + "WHERE substring(dim2, 1, 1) IN (SELECT substring(dim1, 1, 1) FROM druid.foo WHERE dim1 <> '')"
        + "group by dim2",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimFilter(not(selector("dim1", "", null)))
                        .setDimensions(
                            dimensions(new ExtractionDimensionSpec("dim1", "d0", new SubstringDimExtractionFn(0, 1)))
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build(),
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimFilter(in(
                            "dim2",
                            ImmutableList.of("1", "2", "a", "d"),
                            new SubstringDimExtractionFn(0, 1)
                        ))
                        .setDimensions(dimensions(new DefaultDimensionSpec("dim2", "d0")))
                        .setAggregatorSpecs(aggregators(new CountAggregatorFactory("a0")))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{"a", 2L},
            new Object[]{"abc", 1L}
        )
    );
  }

  @Test
  public void testUnicodeFilterAndGroupBy() throws Exception
  {
    testQuery(
        "SELECT\n"
        + "  dim1,\n"
        + "  dim2,\n"
        + "  COUNT(*)\n"
        + "FROM foo2\n"
        + "WHERE\n"
        + "  dim1 LIKE U&'\u05D3\\05E8%'\n" // First char is actually in the string; second is a SQL U& escape
        + "  OR dim1 = 'друид'\n"
        + "GROUP BY dim1, dim2",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE2)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimFilter(or(
                            new LikeDimFilter("dim1", "דר%", null, null),
                            new SelectorDimFilter("dim1", "друид", null)
                        ))
                        .setDimensions(dimensions(
                            new DefaultDimensionSpec("dim1", "d0"),
                            new DefaultDimensionSpec("dim2", "d1")
                        ))
                        .setAggregatorSpecs(aggregators(new CountAggregatorFactory("a0")))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{"друид", "ru", 1L},
            new Object[]{"דרואיד", "he", 1L}
        )
    );
  }

  @Test
  public void testProjectAfterSort() throws Exception
  {
    testQuery(
        "select dim1 from (select dim1, dim2, count(*) cnt from druid.foo group by dim1, dim2 order by cnt)",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("dim1", "d0"),
                                new DefaultDimensionSpec("dim2", "d1")
                            )
                        )
                        .setAggregatorSpecs(aggregators(new CountAggregatorFactory("a0")))
                        .setLimitSpec(
                            new DefaultLimitSpec(
                                Collections.singletonList(
                                    new OrderByColumnSpec("a0", Direction.ASCENDING, StringComparators.NUMERIC)
                                ),
                                Integer.MAX_VALUE
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{""},
            new Object[]{"1"},
            new Object[]{"10.1"},
            new Object[]{"2"},
            new Object[]{"abc"},
            new Object[]{"def"}
        )
    );
  }

  @Test
  public void testProjectAfterSort2() throws Exception
  {
    testQuery(
        "select s / cnt, dim1, dim2, s from (select dim1, dim2, count(*) cnt, sum(m2) s from druid.foo group by dim1, dim2 order by cnt)",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("dim1", "d0"),
                                new DefaultDimensionSpec("dim2", "d1")
                            )
                        )
                        .setAggregatorSpecs(
                            aggregators(new CountAggregatorFactory("a0"), new DoubleSumAggregatorFactory("a1", "m2"))
                        )
                        .setPostAggregatorSpecs(Collections.singletonList(expressionPostAgg(
                            "s0",
                            "(\"a1\" / \"a0\")"
                        )))
                        .setLimitSpec(
                            new DefaultLimitSpec(
                                Collections.singletonList(
                                    new OrderByColumnSpec("a0", Direction.ASCENDING, StringComparators.NUMERIC)
                                ),
                                Integer.MAX_VALUE
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{1.0, "", "a", 1.0},
            new Object[]{4.0, "1", "a", 4.0},
            new Object[]{2.0, "10.1", NullHandling.defaultStringValue(), 2.0},
            new Object[]{3.0, "2", "", 3.0},
            new Object[]{6.0, "abc", NullHandling.defaultStringValue(), 6.0},
            new Object[]{5.0, "def", "abc", 5.0}
        )
    );
  }

  @Test
  public void testProjectAfterSort3() throws Exception
  {
    testQuery(
        "select dim1 from (select dim1, dim1, count(*) cnt from druid.foo group by dim1, dim1 order by cnt)",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("dim1", "d0")
                            )
                        )
                        .setAggregatorSpecs(aggregators(new CountAggregatorFactory("a0")))
                        .setLimitSpec(
                            new DefaultLimitSpec(
                                Collections.singletonList(
                                    new OrderByColumnSpec("a0", Direction.ASCENDING, StringComparators.NUMERIC)
                                ),
                                Integer.MAX_VALUE
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{""},
            new Object[]{"1"},
            new Object[]{"10.1"},
            new Object[]{"2"},
            new Object[]{"abc"},
            new Object[]{"def"}
        )
    );
  }

  @Test
  public void testSortProjectAfterNestedGroupBy() throws Exception
  {
    testQuery(
        "SELECT "
        + "  cnt "
        + "FROM ("
        + "  SELECT "
        + "    __time, "
        + "    dim1, "
        + "    COUNT(m2) AS cnt "
        + "  FROM ("
        + "    SELECT "
        + "        __time, "
        + "        m2, "
        + "        dim1 "
        + "    FROM druid.foo "
        + "    GROUP BY __time, m2, dim1 "
        + "  ) "
        + "  GROUP BY __time, dim1 "
        + "  ORDER BY cnt"
        + ")",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(
                            GroupByQuery.builder()
                                        .setDataSource(CalciteTests.DATASOURCE1)
                                        .setInterval(querySegmentSpec(Filtration.eternity()))
                                        .setGranularity(Granularities.ALL)
                                        .setDimensions(dimensions(
                                            new DefaultDimensionSpec("__time", "d0", ValueType.LONG),
                                            new DefaultDimensionSpec("m2", "d1", ValueType.DOUBLE),
                                            new DefaultDimensionSpec("dim1", "d2")
                                        ))
                                        .setContext(QUERY_CONTEXT_DEFAULT)
                                        .build()
                        )
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimensions(dimensions(
                            new DefaultDimensionSpec("d0", "_d0", ValueType.LONG),
                            new DefaultDimensionSpec("d2", "_d1", ValueType.STRING)
                        ))
                        .setAggregatorSpecs(aggregators(
                            new CountAggregatorFactory("a0")
                        ))
                        .setLimitSpec(
                            new DefaultLimitSpec(
                                Collections.singletonList(
                                    new OrderByColumnSpec("a0", Direction.ASCENDING, StringComparators.NUMERIC)
                                ),
                                Integer.MAX_VALUE
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{1L},
            new Object[]{1L},
            new Object[]{1L},
            new Object[]{1L},
            new Object[]{1L},
            new Object[]{1L}
        )
    );
  }

  @Test
  public void testPostAggWithTimeseries() throws Exception
  {
    // Cannot vectorize due to descending order.
    cannotVectorize();

    testQuery(
        "SELECT "
        + "  FLOOR(__time TO YEAR), "
        + "  SUM(m1), "
        + "  SUM(m1) + SUM(m2) "
        + "FROM "
        + "  druid.foo "
        + "WHERE "
        + "  dim2 = 'a' "
        + "GROUP BY FLOOR(__time TO YEAR) "
        + "ORDER BY FLOOR(__time TO YEAR) desc",
        Collections.singletonList(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .filters(selector("dim2", "a", null))
                  .granularity(Granularities.YEAR)
                  .aggregators(
                      aggregators(
                          new DoubleSumAggregatorFactory("a0", "m1"),
                          new DoubleSumAggregatorFactory("a1", "m2")
                      )
                  )
                  .postAggregators(
                      expressionPostAgg("p0", "(\"a0\" + \"a1\")")
                  )
                  .descending(true)
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{978307200000L, 4.0, 8.0},
            new Object[]{946684800000L, 1.0, 2.0}
        )
    );
  }

  @Test
  public void testPostAggWithTopN() throws Exception
  {
    testQuery(
        "SELECT "
        + "  AVG(m2), "
        + "  SUM(m1) + SUM(m2) "
        + "FROM "
        + "  druid.foo "
        + "WHERE "
        + "  dim2 = 'a' "
        + "GROUP BY m1 "
        + "ORDER BY m1 "
        + "LIMIT 5",
        Collections.singletonList(
            new TopNQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE1)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .granularity(Granularities.ALL)
                .dimension(new DefaultDimensionSpec("m1", "d0", ValueType.FLOAT))
                .filters("dim2", "a")
                .aggregators(
                    new DoubleSumAggregatorFactory("a0:sum", "m2"),
                    new CountAggregatorFactory("a0:count"),
                    new DoubleSumAggregatorFactory("a1", "m1"),
                    new DoubleSumAggregatorFactory("a2", "m2")
                )
                .postAggregators(
                    new ArithmeticPostAggregator(
                        "a0",
                        "quotient",
                        ImmutableList.of(
                            new FieldAccessPostAggregator(null, "a0:sum"),
                            new FieldAccessPostAggregator(null, "a0:count")
                        )
                    ),
                    expressionPostAgg("p0", "(\"a1\" + \"a2\")")
                )
                .metric(new DimensionTopNMetricSpec(null, StringComparators.NUMERIC))
                .threshold(5)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{1.0, 2.0},
            new Object[]{4.0, 8.0}
        )
    );
  }

  @Test
  public void testConcat() throws Exception
  {
    testQuery(
        "SELECT CONCAT(dim1, '-', dim1, '_', dim1) as dimX FROM foo",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE1)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .virtualColumns(expressionVirtualColumn(
                    "v0",
                    "concat(\"dim1\",'-',\"dim1\",'_',\"dim1\")",
                    ValueType.STRING
                ))
                .columns("v0")
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"-_"},
            new Object[]{"10.1-10.1_10.1"},
            new Object[]{"2-2_2"},
            new Object[]{"1-1_1"},
            new Object[]{"def-def_def"},
            new Object[]{"abc-abc_abc"}
        )
    );

    testQuery(
        "SELECT CONCAt(dim1, CONCAt(dim2,'x'), m2, 9999, dim1) as dimX FROM foo",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE1)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .virtualColumns(expressionVirtualColumn(
                    "v0",
                    "concat(\"dim1\",concat(\"dim2\",'x'),\"m2\",9999,\"dim1\")",
                    ValueType.STRING
                ))
                .columns("v0")
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"ax1.09999"},
            new Object[]{NullHandling.sqlCompatible() ? null : "10.1x2.0999910.1"}, // dim2 is null
            new Object[]{"2x3.099992"},
            new Object[]{"1ax4.099991"},
            new Object[]{"defabcx5.09999def"},
            new Object[]{NullHandling.sqlCompatible() ? null : "abcx6.09999abc"} // dim2 is null
        )
    );
  }

  @Test
  public void testTextcat() throws Exception
  {
    testQuery(
        "SELECT textcat(dim1, dim1) as dimX FROM foo",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE1)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .virtualColumns(expressionVirtualColumn("v0", "concat(\"dim1\",\"dim1\")", ValueType.STRING))
                .columns("v0")
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{""},
            new Object[]{"10.110.1"},
            new Object[]{"22"},
            new Object[]{"11"},
            new Object[]{"defdef"},
            new Object[]{"abcabc"}
        )
    );

    testQuery(
        "SELECT textcat(dim1, CAST(m2 as VARCHAR)) as dimX FROM foo",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE1)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .virtualColumns(expressionVirtualColumn(
                    "v0",
                    "concat(\"dim1\",CAST(\"m2\", 'STRING'))",
                    ValueType.STRING
                ))
                .columns("v0")
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"1.0"},
            new Object[]{"10.12.0"},
            new Object[]{"23.0"},
            new Object[]{"14.0"},
            new Object[]{"def5.0"},
            new Object[]{"abc6.0"}
        )
    );
  }

  @Test
  public void testRequireTimeConditionPositive() throws Exception
  {
    // simple timeseries
    testQuery(
        PLANNER_CONFIG_REQUIRE_TIME_CONDITION,
        "SELECT SUM(cnt), gran FROM (\n"
        + "  SELECT __time as t, floor(__time TO month) AS gran,\n"
        + "  cnt FROM druid.foo\n"
        + ") AS x\n"
        + "WHERE t >= '2000-01-01' and t < '2002-01-01'"
        + "GROUP BY gran\n"
        + "ORDER BY gran",
        CalciteTests.REGULAR_USER_AUTH_RESULT,
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Intervals.of("2000-01-01/2002-01-01")))
                  .granularity(Granularities.MONTH)
                  .aggregators(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{3L, timestamp("2000-01-01")},
            new Object[]{3L, timestamp("2001-01-01")}
        )
    );

    // nested groupby only requires time condition for inner most query
    testQuery(
        PLANNER_CONFIG_REQUIRE_TIME_CONDITION,
        "SELECT\n"
        + "  SUM(cnt),\n"
        + "  COUNT(*)\n"
        + "FROM (SELECT dim2, SUM(cnt) AS cnt FROM druid.foo WHERE __time >= '2000-01-01' GROUP BY dim2)",
        CalciteTests.REGULAR_USER_AUTH_RESULT,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(
                            new QueryDataSource(
                                GroupByQuery.builder()
                                            .setDataSource(CalciteTests.DATASOURCE1)
                                            .setInterval(querySegmentSpec(Intervals.utc(
                                                DateTimes.of("2000-01-01").getMillis(),
                                                JodaUtils.MAX_INSTANT
                                            )))
                                            .setGranularity(Granularities.ALL)
                                            .setDimensions(dimensions(new DefaultDimensionSpec("dim2", "d0")))
                                            .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                                            .setContext(QUERY_CONTEXT_DEFAULT)
                                            .build()
                            )
                        )
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setAggregatorSpecs(aggregators(
                            new LongSumAggregatorFactory("_a0", "a0"),
                            new CountAggregatorFactory("_a1")
                        ))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        NullHandling.replaceWithDefault() ?
        ImmutableList.of(
            new Object[]{6L, 3L}
        ) :
        ImmutableList.of(
            new Object[]{6L, 4L}
        )
    );

    // Cannot vectorize next test due to "cardinality" aggregator.
    cannotVectorize();

    // semi-join requires time condition on both left and right query
    testQuery(
        PLANNER_CONFIG_REQUIRE_TIME_CONDITION,
        "SELECT COUNT(*) FROM druid.foo\n"
        + "WHERE __time >= '2000-01-01' AND SUBSTRING(dim2, 1, 1) IN (\n"
        + "  SELECT SUBSTRING(dim1, 1, 1) FROM druid.foo\n"
        + "  WHERE dim1 <> '' AND __time >= '2000-01-01'\n"
        + ")",
        CalciteTests.REGULAR_USER_AUTH_RESULT,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Intervals.utc(
                            DateTimes.of("2000-01-01").getMillis(),
                            JodaUtils.MAX_INSTANT
                        )))
                        .setGranularity(Granularities.ALL)
                        .setDimFilter(not(selector("dim1", "", null)))
                        .setDimensions(dimensions(new ExtractionDimensionSpec(
                            "dim1",
                            "d0",
                            new SubstringDimExtractionFn(0, 1)
                        )))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build(),
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Intervals.utc(
                      DateTimes.of("2000-01-01").getMillis(),
                      JodaUtils.MAX_INSTANT
                  )))
                  .granularity(Granularities.ALL)
                  .filters(in(
                      "dim2",
                      ImmutableList.of("1", "2", "a", "d"),
                      new SubstringDimExtractionFn(0, 1)
                  ))
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .context(TIMESERIES_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{3L}
        )
    );
  }

  @Test
  public void testRequireTimeConditionSimpleQueryNegative() throws Exception
  {
    expectedException.expect(CannotBuildQueryException.class);
    expectedException.expectMessage("__time column");

    testQuery(
        PLANNER_CONFIG_REQUIRE_TIME_CONDITION,
        "SELECT SUM(cnt), gran FROM (\n"
        + "  SELECT __time as t, floor(__time TO month) AS gran,\n"
        + "  cnt FROM druid.foo\n"
        + ") AS x\n"
        + "GROUP BY gran\n"
        + "ORDER BY gran",
        CalciteTests.REGULAR_USER_AUTH_RESULT,
        ImmutableList.of(),
        ImmutableList.of()
    );
  }

  @Test
  public void testRequireTimeConditionSubQueryNegative() throws Exception
  {
    expectedException.expect(CannotBuildQueryException.class);
    expectedException.expectMessage("__time column");

    testQuery(
        PLANNER_CONFIG_REQUIRE_TIME_CONDITION,
        "SELECT\n"
        + "  SUM(cnt),\n"
        + "  COUNT(*)\n"
        + "FROM (SELECT dim2, SUM(cnt) AS cnt FROM druid.foo GROUP BY dim2)",
        CalciteTests.REGULAR_USER_AUTH_RESULT,
        ImmutableList.of(),
        ImmutableList.of()
    );
  }

  @Test
  public void testRequireTimeConditionSemiJoinNegative() throws Exception
  {
    expectedException.expect(CannotBuildQueryException.class);
    expectedException.expectMessage("__time column");

    testQuery(
        PLANNER_CONFIG_REQUIRE_TIME_CONDITION,
        "SELECT COUNT(*) FROM druid.foo\n"
        + "WHERE SUBSTRING(dim2, 1, 1) IN (\n"
        + "  SELECT SUBSTRING(dim1, 1, 1) FROM druid.foo\n"
        + "  WHERE dim1 <> '' AND __time >= '2000-01-01'\n"
        + ")",
        CalciteTests.REGULAR_USER_AUTH_RESULT,
        ImmutableList.of(),
        ImmutableList.of()
    );
  }

  @Test
  public void testFilterFloatDimension() throws Exception
  {
    testQuery(
        "SELECT dim1 FROM numfoo WHERE f1 = 0.1 LIMIT 1",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE3)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .columns("dim1")
                .filters(selector("f1", "0.1", null))
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .limit(1)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"10.1"}
        )
    );
  }

  @Test
  public void testFilterDoubleDimension() throws Exception
  {
    testQuery(
        "SELECT dim1 FROM numfoo WHERE d1 = 1.7 LIMIT 1",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE3)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .columns("dim1")
                .filters(selector("d1", "1.7", null))
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .limit(1)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"10.1"}
        )
    );
  }

  @Test
  public void testFilterLongDimension() throws Exception
  {
    testQuery(
        "SELECT dim1 FROM numfoo WHERE l1 = 7 LIMIT 1",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE3)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .columns("dim1")
                .filters(selector("l1", "7", null))
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .limit(1)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{""}
        )
    );
  }

  @Test
  public void testTrigonometricFunction() throws Exception
  {
    testQuery(
        PLANNER_CONFIG_DEFAULT,
        QUERY_CONTEXT_DONT_SKIP_EMPTY_BUCKETS,
        "SELECT exp(count(*)) + 10, sin(pi / 6), cos(pi / 6), tan(pi / 6), cot(pi / 6)," +
        "asin(exp(count(*)) / 2), acos(exp(count(*)) / 2), atan(exp(count(*)) / 2), atan2(exp(count(*)), 1) " +
        "FROM druid.foo WHERE  dim2 = 0",
        CalciteTests.REGULAR_USER_AUTH_RESULT,
        ImmutableList.of(Druids.newTimeseriesQueryBuilder()
                               .dataSource(CalciteTests.DATASOURCE1)
                               .intervals(querySegmentSpec(Filtration.eternity()))
                               .filters(selector("dim2", "0", null))
                               .granularity(Granularities.ALL)
                               .aggregators(aggregators(
                                   new CountAggregatorFactory("a0")
                               ))
                               .postAggregators(
                                   expressionPostAgg("p0", "(exp(\"a0\") + 10)"),
                                   expressionPostAgg("p1", "sin((pi() / 6))"),
                                   expressionPostAgg("p2", "cos((pi() / 6))"),
                                   expressionPostAgg("p3", "tan((pi() / 6))"),
                                   expressionPostAgg("p4", "cot((pi() / 6))"),
                                   expressionPostAgg("p5", "asin((exp(\"a0\") / 2))"),
                                   expressionPostAgg("p6", "acos((exp(\"a0\") / 2))"),
                                   expressionPostAgg("p7", "atan((exp(\"a0\") / 2))"),
                                   expressionPostAgg("p8", "atan2(exp(\"a0\"),1)")
                               )
                               .context(QUERY_CONTEXT_DONT_SKIP_EMPTY_BUCKETS)
                               .build()),
        ImmutableList.of(
            new Object[]{
                11.0,
                Math.sin(Math.PI / 6),
                Math.cos(Math.PI / 6),
                Math.tan(Math.PI / 6),
                Math.cos(Math.PI / 6) / Math.sin(Math.PI / 6),
                Math.asin(0.5),
                Math.acos(0.5),
                Math.atan(0.5),
                Math.atan2(1, 1)
            }
        )
    );
  }

  @Test
  public void testRadiansAndDegrees() throws Exception
  {
    testQuery(
        "SELECT RADIANS(m1 * 15)/DEGREES(m2) FROM numfoo WHERE dim1 = '1'",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE3)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .virtualColumns(
                    expressionVirtualColumn("v0", "(toRadians((\"m1\" * 15)) / toDegrees(\"m2\"))", ValueType.DOUBLE)
                )
                .columns("v0")
                .filters(selector("dim1", "1", null))
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{Math.toRadians(60) / Math.toDegrees(4)}
        )
    );
  }

  @Test
  public void testTimestampDiff() throws Exception
  {
    testQuery(
        "SELECT TIMESTAMPDIFF(DAY, TIMESTAMP '1999-01-01 00:00:00', __time), \n"
        + "TIMESTAMPDIFF(DAY, __time, DATE '2001-01-01'), \n"
        + "TIMESTAMPDIFF(HOUR, TIMESTAMP '1999-12-31 01:00:00', __time), \n"
        + "TIMESTAMPDIFF(MINUTE, TIMESTAMP '1999-12-31 23:58:03', __time), \n"
        + "TIMESTAMPDIFF(SECOND, TIMESTAMP '1999-12-31 23:59:03', __time), \n"
        + "TIMESTAMPDIFF(MONTH, TIMESTAMP '1999-11-01 00:00:00', __time), \n"
        + "TIMESTAMPDIFF(YEAR, TIMESTAMP '1996-11-01 00:00:00', __time), \n"
        + "TIMESTAMPDIFF(QUARTER, TIMESTAMP '1996-10-01 00:00:00', __time), \n"
        + "TIMESTAMPDIFF(WEEK, TIMESTAMP '1998-10-01 00:00:00', __time) \n"
        + "FROM druid.foo\n"
        + "LIMIT 2",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE1)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .virtualColumns(
                    expressionVirtualColumn("v0", "div((\"__time\" - 915148800000),86400000)", ValueType.LONG),
                    expressionVirtualColumn("v1", "div((978307200000 - \"__time\"),86400000)", ValueType.LONG),
                    expressionVirtualColumn("v2", "div((\"__time\" - 946602000000),3600000)", ValueType.LONG),
                    expressionVirtualColumn("v3", "div((\"__time\" - 946684683000),60000)", ValueType.LONG),
                    expressionVirtualColumn("v4", "div((\"__time\" - 946684743000),1000)", ValueType.LONG),
                    expressionVirtualColumn("v5", "subtract_months(\"__time\",941414400000,'UTC')", ValueType.LONG),
                    expressionVirtualColumn(
                        "v6",
                        "div(subtract_months(\"__time\",846806400000,'UTC'),12)",
                        ValueType.LONG
                    ),
                    expressionVirtualColumn(
                        "v7",
                        "div(subtract_months(\"__time\",844128000000,'UTC'),3)",
                        ValueType.LONG
                    ),
                    expressionVirtualColumn("v8", "div(div((\"__time\" - 907200000000),1000),604800)", ValueType.LONG)
                )
                .columns("v0", "v1", "v2", "v3", "v4", "v5", "v6", "v7", "v8")
                .limit(2)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()

        ),
        ImmutableList.of(
            new Object[]{365, 366, 23, 1, 57, 2, 3, 13, 65},
            new Object[]{366, 365, 47, 1441, 86457, 2, 3, 13, 65}
        )
    );
  }

  @Test
  public void testTimestampCeil() throws Exception
  {
    testQuery(
        "SELECT CEIL(TIMESTAMP '2000-01-01 00:00:00' TO DAY), \n"
        + "CEIL(TIMESTAMP '2000-01-01 01:00:00' TO DAY) \n"
        + "FROM druid.foo\n"
        + "LIMIT 1",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE1)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .virtualColumns(
                    expressionVirtualColumn("v0", "946684800000", ValueType.LONG),
                    expressionVirtualColumn("v1", "946771200000", ValueType.LONG)
                )
                .columns("v0", "v1")
                .limit(1)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()

        ),
        ImmutableList.of(
            new Object[]{
                Calcites.jodaToCalciteTimestamp(
                    DateTimes.of("2000-01-01"),
                    DateTimeZone.UTC
                ),
                Calcites.jodaToCalciteTimestamp(
                    DateTimes.of("2000-01-02"),
                    DateTimeZone.UTC
                )
            }
        )
    );
  }

  @Test
  public void testNvlColumns() throws Exception
  {
    // Cannot vectorize due to usage of expressions.
    cannotVectorize();

    testQuery(
        "SELECT NVL(dim2, dim1), COUNT(*) FROM druid.foo GROUP BY NVL(dim2, dim1)\n",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE1)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(
                            expressionVirtualColumn(
                                "v0",
                                "case_searched(notnull(\"dim2\"),\"dim2\",\"dim1\")",
                                ValueType.STRING
                            )
                        )
                        .setDimensions(dimensions(new DefaultDimensionSpec("v0", "v0", ValueType.STRING)))
                        .setAggregatorSpecs(aggregators(new CountAggregatorFactory("a0")))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        NullHandling.replaceWithDefault() ?
        ImmutableList.of(
            new Object[]{"10.1", 1L},
            new Object[]{"2", 1L},
            new Object[]{"a", 2L},
            new Object[]{"abc", 2L}
        ) :
        ImmutableList.of(
            new Object[]{"", 1L},
            new Object[]{"10.1", 1L},
            new Object[]{"a", 2L},
            new Object[]{"abc", 2L}
        )
    );
  }

  @Test
  public void testMultiValueStringWorksLikeStringGroupBy() throws Exception
  {
    // Cannot vectorize due to usage of expressions.
    cannotVectorize();

    List<Object[]> expected;
    if (NullHandling.replaceWithDefault()) {
      expected = ImmutableList.of(
          new Object[]{"bfoo", 2L},
          new Object[]{"foo", 2L},
          new Object[]{"", 1L},
          new Object[]{"afoo", 1L},
          new Object[]{"cfoo", 1L},
          new Object[]{"dfoo", 1L}
      );
    } else {
      expected = ImmutableList.of(
          new Object[]{null, 2L},
          new Object[]{"bfoo", 2L},
          new Object[]{"afoo", 1L},
          new Object[]{"cfoo", 1L},
          new Object[]{"dfoo", 1L},
          new Object[]{"foo", 1L}
      );
    }
    testQuery(
        "SELECT concat(dim3, 'foo'), SUM(cnt) FROM druid.numfoo GROUP BY 1 ORDER BY 2 DESC",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE3)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(expressionVirtualColumn("v0", "concat(\"dim3\",'foo')", ValueType.STRING))
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("v0", "v0", ValueType.STRING)
                            )
                        )
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(new DefaultLimitSpec(
                            ImmutableList.of(new OrderByColumnSpec(
                                "a0",
                                Direction.DESCENDING,
                                StringComparators.NUMERIC
                            )),
                            Integer.MAX_VALUE
                        ))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        expected
    );
  }

  @Test
  public void testMultiValueStringWorksLikeStringGroupByWithFilter() throws Exception
  {
    // Cannot vectorize due to usage of expressions.
    cannotVectorize();

    testQuery(
        "SELECT concat(dim3, 'foo'), SUM(cnt) FROM druid.numfoo where concat(dim3, 'foo') = 'bfoo' GROUP BY 1 ORDER BY 2 DESC",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE3)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(expressionVirtualColumn("v0", "concat(\"dim3\",'foo')", ValueType.STRING))
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("v0", "v0", ValueType.STRING)
                            )
                        )
                        .setDimFilter(selector("v0", "bfoo", null))
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(new DefaultLimitSpec(
                            ImmutableList.of(new OrderByColumnSpec(
                                "a0",
                                Direction.DESCENDING,
                                StringComparators.NUMERIC
                            )),
                            Integer.MAX_VALUE
                        ))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{"bfoo", 2L},
            new Object[]{"afoo", 1L},
            new Object[]{"cfoo", 1L}
        )
    );
  }

  @Test
  public void testMultiValueStringWorksLikeStringScan() throws Exception
  {
    final String nullVal = NullHandling.replaceWithDefault() ? "[\"foo\"]" : "[null]";
    testQuery(
        "SELECT concat(dim3, 'foo') FROM druid.numfoo",
        ImmutableList.of(
            new Druids.ScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE3)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .virtualColumns(expressionVirtualColumn("v0", "concat(\"dim3\",'foo')", ValueType.STRING))
                .columns(ImmutableList.of("v0"))
                .context(QUERY_CONTEXT_DEFAULT)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .legacy(false)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"[\"afoo\",\"bfoo\"]"},
            new Object[]{"[\"bfoo\",\"cfoo\"]"},
            new Object[]{"[\"dfoo\"]"},
            new Object[]{"[\"foo\"]"},
            new Object[]{nullVal},
            new Object[]{nullVal}
        )
    );
  }

  @Test
  public void testMultiValueStringWorksLikeStringSelfConcatScan() throws Exception
  {
    final String nullVal = NullHandling.replaceWithDefault() ? "[\"-lol-\"]" : "[null]";
    testQuery(
        "SELECT concat(dim3, '-lol-', dim3) FROM druid.numfoo",
        ImmutableList.of(
            new Druids.ScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE3)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .virtualColumns(expressionVirtualColumn("v0", "concat(\"dim3\",'-lol-',\"dim3\")", ValueType.STRING))
                .columns(ImmutableList.of("v0"))
                .context(QUERY_CONTEXT_DEFAULT)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .legacy(false)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"[\"a-lol-a\",\"a-lol-b\",\"b-lol-a\",\"b-lol-b\"]"},
            new Object[]{"[\"b-lol-b\",\"b-lol-c\",\"c-lol-b\",\"c-lol-c\"]"},
            new Object[]{"[\"d-lol-d\"]"},
            new Object[]{"[\"-lol-\"]"},
            new Object[]{nullVal},
            new Object[]{nullVal}
        )
    );
  }

  @Test
  public void testMultiValueStringWorksLikeStringScanWithFilter() throws Exception
  {
    testQuery(
        "SELECT concat(dim3, 'foo') FROM druid.numfoo where concat(dim3, 'foo') = 'bfoo'",
        ImmutableList.of(
            new Druids.ScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE3)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .virtualColumns(expressionVirtualColumn("v0", "concat(\"dim3\",'foo')", ValueType.STRING))
                .filters(selector("v0", "bfoo", null))
                .columns(ImmutableList.of("v0"))
                .context(QUERY_CONTEXT_DEFAULT)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .legacy(false)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"[\"afoo\",\"bfoo\"]"},
            new Object[]{"[\"bfoo\",\"cfoo\"]"}
        )
    );
  }

  @Test
  public void testSelectConstantArrayExpressionFromTable() throws Exception
  {
    testQuery(
        "SELECT ARRAY[1,2] as arr, dim1 FROM foo LIMIT 1",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE1)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .virtualColumns(expressionVirtualColumn("v0", "array(1,2)", ValueType.STRING))
                .columns("dim1", "v0")
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .limit(1)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"[\"1\",\"2\"]", ""}
        )
    );
  }

  @Test
  public void testSelectNonConstantArrayExpressionFromTable() throws Exception
  {
    testQuery(
        "SELECT ARRAY[CONCAT(dim1, 'word'),'up'] as arr, dim1 FROM foo LIMIT 5",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE1)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .virtualColumns(expressionVirtualColumn("v0", "array(concat(\"dim1\",'word'),'up')", ValueType.STRING))
                .columns("dim1", "v0")
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .limit(5)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"[\"word\",\"up\"]", ""},
            new Object[]{"[\"10.1word\",\"up\"]", "10.1"},
            new Object[]{"[\"2word\",\"up\"]", "2"},
            new Object[]{"[\"1word\",\"up\"]", "1"},
            new Object[]{"[\"defword\",\"up\"]", "def"}
        )
    );
  }

  @Test
  public void testSelectNonConstantArrayExpressionFromTableFailForMultival() throws Exception
  {
    // without expression output type inference to prevent this, the automatic translation will try to turn this into
    //
    //    `map((dim3) -> array(concat(dim3,'word'),'up'), dim3)`
    //
    // This error message will get better in the future. The error without translation would be:
    //
    //    org.apache.druid.java.util.common.RE: Unhandled array constructor element type [STRING_ARRAY]

    expectedException.expect(RuntimeException.class);
    expectedException.expectMessage("Unhandled map function output type [STRING_ARRAY]");
    testQuery(
        "SELECT ARRAY[CONCAT(dim3, 'word'),'up'] as arr, dim1 FROM foo LIMIT 5",
        ImmutableList.of(),
        ImmutableList.of()
    );
  }

  @Test
  public void testMultiValueStringOverlapFilter() throws Exception
  {
    testQuery(
        "SELECT dim3 FROM druid.numfoo WHERE MV_OVERLAP(dim3, ARRAY['a','b']) LIMIT 5",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE3)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .filters(expressionFilter("array_overlap(\"dim3\",array('a','b'))"))
                .columns("dim3")
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .limit(5)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"[\"a\",\"b\"]"},
            new Object[]{"[\"b\",\"c\"]"}
        )
    );
  }

  @Test
  public void testMultiValueStringOverlapFilterNonConstant() throws Exception
  {
    testQuery(
        "SELECT dim3 FROM druid.numfoo WHERE MV_OVERLAP(dim3, ARRAY['a','b']) LIMIT 5",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE3)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .filters(expressionFilter("array_overlap(\"dim3\",array('a','b'))"))
                .columns("dim3")
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .limit(5)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"[\"a\",\"b\"]"},
            new Object[]{"[\"b\",\"c\"]"}
        )
    );
  }

  @Test
  public void testMultiValueStringContainsFilter() throws Exception
  {
    testQuery(
        "SELECT dim3 FROM druid.numfoo WHERE MV_CONTAINS(dim3, ARRAY['a','b']) LIMIT 5",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE3)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .filters(expressionFilter("array_contains(\"dim3\",array('a','b'))"))
                .columns("dim3")
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .limit(5)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"[\"a\",\"b\"]"}
        )
    );
  }

  @Test
  public void testMultiValueStringSlice() throws Exception
  {
    testQuery(
        "SELECT MV_SLICE(dim3, 1) FROM druid.numfoo",
        ImmutableList.of(
            new Druids.ScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE3)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .virtualColumns(expressionVirtualColumn("v0", "array_slice(\"dim3\",1)", ValueType.STRING))
                .columns(ImmutableList.of("v0"))
                .context(QUERY_CONTEXT_DEFAULT)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .legacy(false)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"[\"b\"]"},
            new Object[]{"[\"c\"]"},
            new Object[]{"[]"},
            new Object[]{"[]"},
            new Object[]{"[]"},
            new Object[]{"[]"}
        )
    );
  }

  @Test
  public void testMultiValueStringLength() throws Exception
  {
    // Cannot vectorize due to usage of expressions.
    cannotVectorize();

    testQuery(
        "SELECT dim1, MV_LENGTH(dim3), SUM(cnt) FROM druid.numfoo GROUP BY 1, 2 ORDER BY 2 DESC",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE3)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(expressionVirtualColumn("v0", "array_length(\"dim3\")", ValueType.LONG))
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("dim1", "_d0", ValueType.STRING),
                                new DefaultDimensionSpec("v0", "v0", ValueType.LONG)
                            )
                        )
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(new DefaultLimitSpec(
                            ImmutableList.of(new OrderByColumnSpec(
                                "v0",
                                Direction.DESCENDING,
                                StringComparators.NUMERIC
                            )),
                            Integer.MAX_VALUE
                        ))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{"", 2, 1L},
            new Object[]{"10.1", 2, 1L},
            new Object[]{"1", 1, 1L},
            new Object[]{"2", 1, 1L},
            new Object[]{"abc", 1, 1L},
            new Object[]{"def", 1, 1L}
        )
    );
  }

  @Test
  public void testMultiValueStringAppend() throws Exception
  {
    // Cannot vectorize due to usage of expressions.
    cannotVectorize();

    ImmutableList<Object[]> results;
    if (NullHandling.replaceWithDefault()) {
      results = ImmutableList.of(
          new Object[]{"foo", 6L},
          new Object[]{"", 3L},
          new Object[]{"b", 2L},
          new Object[]{"a", 1L},
          new Object[]{"c", 1L},
          new Object[]{"d", 1L}
      );
    } else {
      results = ImmutableList.of(
          new Object[]{"foo", 6L},
          new Object[]{null, 2L},
          new Object[]{"b", 2L},
          new Object[]{"", 1L},
          new Object[]{"a", 1L},
          new Object[]{"c", 1L},
          new Object[]{"d", 1L}
      );
    }
    testQuery(
        "SELECT MV_APPEND(dim3, 'foo'), SUM(cnt) FROM druid.numfoo GROUP BY 1 ORDER BY 2 DESC",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE3)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(expressionVirtualColumn("v0", "array_append(\"dim3\",'foo')", ValueType.STRING))
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("v0", "v0", ValueType.STRING)
                            )
                        )
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(new DefaultLimitSpec(
                            ImmutableList.of(new OrderByColumnSpec(
                                "a0",
                                Direction.DESCENDING,
                                StringComparators.NUMERIC
                            )),
                            Integer.MAX_VALUE
                        ))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        results
    );
  }

  @Test
  public void testMultiValueStringPrepend() throws Exception
  {
    // Cannot vectorize due to usage of expressions.
    cannotVectorize();

    ImmutableList<Object[]> results;
    if (NullHandling.replaceWithDefault()) {
      results = ImmutableList.of(
          new Object[]{"foo", 6L},
          new Object[]{"", 3L},
          new Object[]{"b", 2L},
          new Object[]{"a", 1L},
          new Object[]{"c", 1L},
          new Object[]{"d", 1L}
      );
    } else {
      results = ImmutableList.of(
          new Object[]{"foo", 6L},
          new Object[]{null, 2L},
          new Object[]{"b", 2L},
          new Object[]{"", 1L},
          new Object[]{"a", 1L},
          new Object[]{"c", 1L},
          new Object[]{"d", 1L}
      );
    }
    testQuery(
        "SELECT MV_PREPEND('foo', dim3), SUM(cnt) FROM druid.numfoo GROUP BY 1 ORDER BY 2 DESC",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE3)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(expressionVirtualColumn("v0", "array_prepend('foo',\"dim3\")", ValueType.STRING))
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("v0", "v0", ValueType.STRING)
                            )
                        )
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(new DefaultLimitSpec(
                            ImmutableList.of(new OrderByColumnSpec(
                                "a0",
                                Direction.DESCENDING,
                                StringComparators.NUMERIC
                            )),
                            Integer.MAX_VALUE
                        ))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        results
    );
  }

  @Test
  public void testMultiValueStringPrependAppend() throws Exception
  {
    // Cannot vectorize due to usage of expressions.
    cannotVectorize();

    ImmutableList<Object[]> results;
    if (NullHandling.replaceWithDefault()) {
      results = ImmutableList.of(
          new Object[]{"foo,null", "null,foo", 3L},
          new Object[]{"foo,a,b", "a,b,foo", 1L},
          new Object[]{"foo,b,c", "b,c,foo", 1L},
          new Object[]{"foo,d", "d,foo", 1L}
      );
    } else {
      results = ImmutableList.of(
          new Object[]{"foo,null", "null,foo", 2L},
          new Object[]{"foo,", ",foo", 1L},
          new Object[]{"foo,a,b", "a,b,foo", 1L},
          new Object[]{"foo,b,c", "b,c,foo", 1L},
          new Object[]{"foo,d", "d,foo", 1L}
      );
    }
    testQuery(
        "SELECT MV_TO_STRING(MV_PREPEND('foo', dim3), ','), MV_TO_STRING(MV_APPEND(dim3, 'foo'), ','), SUM(cnt) FROM druid.numfoo GROUP BY 1,2 ORDER BY 3 DESC",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE3)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(
                            expressionVirtualColumn("v0", "array_to_string(array_prepend('foo',\"dim3\"),',')", ValueType.STRING),
                            expressionVirtualColumn("v1", "array_to_string(array_append(\"dim3\",'foo'),',')", ValueType.STRING)
                        )
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("v0", "v0", ValueType.STRING),
                                new DefaultDimensionSpec("v1", "v1", ValueType.STRING)
                            )
                        )
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(new DefaultLimitSpec(
                            ImmutableList.of(new OrderByColumnSpec(
                                "a0",
                                Direction.DESCENDING,
                                StringComparators.NUMERIC
                            )),
                            Integer.MAX_VALUE
                        ))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        results
    );
  }

  @Test
  public void testMultiValueStringConcat() throws Exception
  {
    // Cannot vectorize due to usage of expressions.
    cannotVectorize();

    ImmutableList<Object[]> results;
    if (NullHandling.replaceWithDefault()) {
      results = ImmutableList.of(
          new Object[]{"", 6L},
          new Object[]{"b", 4L},
          new Object[]{"a", 2L},
          new Object[]{"c", 2L},
          new Object[]{"d", 2L}
      );
    } else {
      results = ImmutableList.of(
          new Object[]{null, 4L},
          new Object[]{"b", 4L},
          new Object[]{"", 2L},
          new Object[]{"a", 2L},
          new Object[]{"c", 2L},
          new Object[]{"d", 2L}
      );
    }
    testQuery(
        "SELECT MV_CONCAT(dim3, dim3), SUM(cnt) FROM druid.numfoo GROUP BY 1 ORDER BY 2 DESC",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE3)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(expressionVirtualColumn("v0", "array_concat(\"dim3\",\"dim3\")", ValueType.STRING))
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("v0", "v0", ValueType.STRING)
                            )
                        )
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(new DefaultLimitSpec(
                            ImmutableList.of(new OrderByColumnSpec(
                                "a0",
                                Direction.DESCENDING,
                                StringComparators.NUMERIC
                            )),
                            Integer.MAX_VALUE
                        ))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        results
    );
  }

  @Test
  public void testMultiValueStringOffset() throws Exception
  {
    // Cannot vectorize due to usage of expressions.
    cannotVectorize();

    testQuery(
        "SELECT MV_OFFSET(dim3, 1), SUM(cnt) FROM druid.numfoo GROUP BY 1 ORDER BY 2 DESC",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE3)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(expressionVirtualColumn("v0", "array_offset(\"dim3\",1)", ValueType.STRING))
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("v0", "v0", ValueType.STRING)
                            )
                        )
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(new DefaultLimitSpec(
                            ImmutableList.of(new OrderByColumnSpec(
                                "a0",
                                Direction.DESCENDING,
                                StringComparators.NUMERIC
                            )),
                            Integer.MAX_VALUE
                        ))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{NullHandling.defaultStringValue(), 4L},
            new Object[]{"b", 1L},
            new Object[]{"c", 1L}
        )
    );
  }

  @Test
  public void testMultiValueStringOrdinal() throws Exception
  {
    // Cannot vectorize due to usage of expressions.
    cannotVectorize();

    testQuery(
        "SELECT MV_ORDINAL(dim3, 2), SUM(cnt) FROM druid.numfoo GROUP BY 1 ORDER BY 2 DESC",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE3)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(expressionVirtualColumn("v0", "array_ordinal(\"dim3\",2)", ValueType.STRING))
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("v0", "v0", ValueType.STRING)
                            )
                        )
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(new DefaultLimitSpec(
                            ImmutableList.of(new OrderByColumnSpec(
                                "a0",
                                Direction.DESCENDING,
                                StringComparators.NUMERIC
                            )),
                            Integer.MAX_VALUE
                        ))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{NullHandling.defaultStringValue(), 4L},
            new Object[]{"b", 1L},
            new Object[]{"c", 1L}
        )
    );
  }

  @Test
  public void testMultiValueStringOffsetOf() throws Exception
  {
    // Cannot vectorize due to usage of expressions.
    cannotVectorize();

    testQuery(
        "SELECT MV_OFFSET_OF(dim3, 'b'), SUM(cnt) FROM druid.numfoo GROUP BY 1 ORDER BY 2 DESC",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE3)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(expressionVirtualColumn("v0", "array_offset_of(\"dim3\",'b')", ValueType.LONG))
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("v0", "v0", ValueType.LONG)
                            )
                        )
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(new DefaultLimitSpec(
                            ImmutableList.of(new OrderByColumnSpec(
                                "a0",
                                Direction.DESCENDING,
                                StringComparators.NUMERIC
                            )),
                            Integer.MAX_VALUE
                        ))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{NullHandling.replaceWithDefault() ? -1 : null, 4L},
            new Object[]{0, 1L},
            new Object[]{1, 1L}
        )
    );
  }

  @Test
  public void testMultiValueStringOrdinalOf() throws Exception
  {
    // Cannot vectorize due to usage of expressions.
    cannotVectorize();

    testQuery(
        "SELECT MV_ORDINAL_OF(dim3, 'b'), SUM(cnt) FROM druid.numfoo GROUP BY 1 ORDER BY 2 DESC",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE3)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(expressionVirtualColumn("v0", "array_ordinal_of(\"dim3\",'b')", ValueType.LONG))
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("v0", "v0", ValueType.LONG)
                            )
                        )
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(new DefaultLimitSpec(
                            ImmutableList.of(new OrderByColumnSpec(
                                "a0",
                                Direction.DESCENDING,
                                StringComparators.NUMERIC
                            )),
                            Integer.MAX_VALUE
                        ))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{NullHandling.replaceWithDefault() ? -1 : null, 4L},
            new Object[]{1, 1L},
            new Object[]{2, 1L}
        )
    );
  }

  @Test
  public void testMultiValueStringToString() throws Exception
  {
    // Cannot vectorize due to usage of expressions.
    cannotVectorize();

    ImmutableList<Object[]> results;
    if (NullHandling.replaceWithDefault()) {
      results = ImmutableList.of(
          new Object[]{"", 3L},
          new Object[]{"a,b", 1L},
          new Object[]{"b,c", 1L},
          new Object[]{"d", 1L}
      );
    } else {
      results = ImmutableList.of(
          new Object[]{null, 2L},
          new Object[]{"", 1L},
          new Object[]{"a,b", 1L},
          new Object[]{"b,c", 1L},
          new Object[]{"d", 1L}
      );
    }
    testQuery(
        "SELECT MV_TO_STRING(dim3, ','), SUM(cnt) FROM druid.numfoo GROUP BY 1 ORDER BY 2 DESC",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE3)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(expressionVirtualColumn("v0", "array_to_string(\"dim3\",',')", ValueType.STRING))
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("v0", "v0", ValueType.STRING)
                            )
                        )
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(new DefaultLimitSpec(
                            ImmutableList.of(new OrderByColumnSpec(
                                "a0",
                                Direction.DESCENDING,
                                StringComparators.NUMERIC
                            )),
                            Integer.MAX_VALUE
                        ))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        results
    );
  }

  @Test
  public void testMultiValueStringToStringToMultiValueString() throws Exception
  {
    // Cannot vectorize due to usage of expressions.
    cannotVectorize();

    ImmutableList<Object[]> results;
    if (NullHandling.replaceWithDefault()) {
      results = ImmutableList.of(
          new Object[]{"d", 7L},
          new Object[]{"", 3L},
          new Object[]{"b", 2L},
          new Object[]{"a", 1L},
          new Object[]{"c", 1L}
      );
    } else {
      results = ImmutableList.of(
          new Object[]{"d", 5L},
          new Object[]{null, 2L},
          new Object[]{"b", 2L},
          new Object[]{"", 1L},
          new Object[]{"a", 1L},
          new Object[]{"c", 1L}
      );
    }
    testQuery(
        "SELECT STRING_TO_MV(CONCAT(MV_TO_STRING(dim3, ','), ',d'), ','), SUM(cnt) FROM druid.numfoo WHERE MV_LENGTH(dim3) > 0 GROUP BY 1 ORDER BY 2 DESC",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE3)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(
                            expressionVirtualColumn("v0", "array_length(\"dim3\")", ValueType.LONG),
                            expressionVirtualColumn(
                                "v1",
                                "string_to_array(concat(array_to_string(\"dim3\",','),',d'),',')",
                                ValueType.STRING
                            )
                        )
                        .setDimFilter(bound("v0", "0", null, true, false, null, StringComparators.NUMERIC))
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("v1", "v1", ValueType.STRING)
                            )
                        )
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(new DefaultLimitSpec(
                            ImmutableList.of(new OrderByColumnSpec(
                                "a0",
                                Direction.DESCENDING,
                                StringComparators.NUMERIC
                            )),
                            Integer.MAX_VALUE
                        ))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        results
    );
  }
}
