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
package io.prestosql.sql.planner;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.BaseEncoding;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.prestosql.metadata.Metadata;
import io.prestosql.security.AllowAllAccessControl;
import io.prestosql.spi.predicate.Domain;
import io.prestosql.spi.predicate.Range;
import io.prestosql.spi.predicate.TupleDomain;
import io.prestosql.spi.predicate.ValueSet;
import io.prestosql.spi.type.DecimalType;
import io.prestosql.spi.type.DoubleType;
import io.prestosql.spi.type.RealType;
import io.prestosql.spi.type.Type;
import io.prestosql.sql.planner.DomainTranslator.ExtractionResult;
import io.prestosql.sql.tree.BetweenPredicate;
import io.prestosql.sql.tree.Cast;
import io.prestosql.sql.tree.ComparisonExpression;
import io.prestosql.sql.tree.DoubleLiteral;
import io.prestosql.sql.tree.Expression;
import io.prestosql.sql.tree.FunctionCall;
import io.prestosql.sql.tree.GenericLiteral;
import io.prestosql.sql.tree.InListExpression;
import io.prestosql.sql.tree.InPredicate;
import io.prestosql.sql.tree.IsNullPredicate;
import io.prestosql.sql.tree.Literal;
import io.prestosql.sql.tree.LongLiteral;
import io.prestosql.sql.tree.NotExpression;
import io.prestosql.sql.tree.NullLiteral;
import io.prestosql.sql.tree.QualifiedName;
import io.prestosql.sql.tree.StringLiteral;
import io.prestosql.transaction.TestingTransactionManager;
import io.prestosql.type.TypeCoercion;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.airlift.slice.Slices.utf8Slice;
import static io.prestosql.SessionTestUtils.TEST_SESSION;
import static io.prestosql.metadata.MetadataManager.createTestMetadataManager;
import static io.prestosql.spi.predicate.TupleDomain.withColumnDomains;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.BooleanType.BOOLEAN;
import static io.prestosql.spi.type.CharType.createCharType;
import static io.prestosql.spi.type.DateType.DATE;
import static io.prestosql.spi.type.DecimalType.createDecimalType;
import static io.prestosql.spi.type.Decimals.encodeScaledValue;
import static io.prestosql.spi.type.DoubleType.DOUBLE;
import static io.prestosql.spi.type.HyperLogLogType.HYPER_LOG_LOG;
import static io.prestosql.spi.type.IntegerType.INTEGER;
import static io.prestosql.spi.type.RealType.REAL;
import static io.prestosql.spi.type.SmallintType.SMALLINT;
import static io.prestosql.spi.type.TimestampType.TIMESTAMP;
import static io.prestosql.spi.type.TinyintType.TINYINT;
import static io.prestosql.spi.type.VarbinaryType.VARBINARY;
import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static io.prestosql.sql.ExpressionUtils.and;
import static io.prestosql.sql.ExpressionUtils.or;
import static io.prestosql.sql.analyzer.TypeSignatureTranslator.toSqlType;
import static io.prestosql.sql.tree.BooleanLiteral.FALSE_LITERAL;
import static io.prestosql.sql.tree.BooleanLiteral.TRUE_LITERAL;
import static io.prestosql.sql.tree.ComparisonExpression.Operator.EQUAL;
import static io.prestosql.sql.tree.ComparisonExpression.Operator.GREATER_THAN;
import static io.prestosql.sql.tree.ComparisonExpression.Operator.GREATER_THAN_OR_EQUAL;
import static io.prestosql.sql.tree.ComparisonExpression.Operator.IS_DISTINCT_FROM;
import static io.prestosql.sql.tree.ComparisonExpression.Operator.LESS_THAN;
import static io.prestosql.sql.tree.ComparisonExpression.Operator.LESS_THAN_OR_EQUAL;
import static io.prestosql.sql.tree.ComparisonExpression.Operator.NOT_EQUAL;
import static io.prestosql.testing.TestingConnectorSession.SESSION;
import static io.prestosql.transaction.TransactionBuilder.transaction;
import static io.prestosql.type.ColorType.COLOR;
import static java.lang.String.format;
import static java.util.Collections.nCopies;
import static java.util.Objects.requireNonNull;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

public class TestDomainTranslator
{
    private static final Symbol C_BIGINT = new Symbol("c_bigint");
    private static final Symbol C_DOUBLE = new Symbol("c_double");
    private static final Symbol C_VARCHAR = new Symbol("c_varchar");
    private static final Symbol C_BOOLEAN = new Symbol("c_boolean");
    private static final Symbol C_BIGINT_1 = new Symbol("c_bigint_1");
    private static final Symbol C_DOUBLE_1 = new Symbol("c_double_1");
    private static final Symbol C_VARCHAR_1 = new Symbol("c_varchar_1");
    private static final Symbol C_TIMESTAMP = new Symbol("c_timestamp");
    private static final Symbol C_DATE = new Symbol("c_date");
    private static final Symbol C_COLOR = new Symbol("c_color");
    private static final Symbol C_HYPER_LOG_LOG = new Symbol("c_hyper_log_log");
    private static final Symbol C_VARBINARY = new Symbol("c_varbinary");
    private static final Symbol C_DECIMAL_26_5 = new Symbol("c_decimal_26_5");
    private static final Symbol C_DECIMAL_23_4 = new Symbol("c_decimal_23_4");
    private static final Symbol C_INTEGER = new Symbol("c_integer");
    private static final Symbol C_CHAR = new Symbol("c_char");
    private static final Symbol C_DECIMAL_21_3 = new Symbol("c_decimal_21_3");
    private static final Symbol C_DECIMAL_12_2 = new Symbol("c_decimal_12_2");
    private static final Symbol C_DECIMAL_6_1 = new Symbol("c_decimal_6_1");
    private static final Symbol C_DECIMAL_3_0 = new Symbol("c_decimal_3_0");
    private static final Symbol C_DECIMAL_2_0 = new Symbol("c_decimal_2_0");
    private static final Symbol C_SMALLINT = new Symbol("c_smallint");
    private static final Symbol C_TINYINT = new Symbol("c_tinyint");
    private static final Symbol C_REAL = new Symbol("c_real");

    private static final TypeProvider TYPES = TypeProvider.copyOf(ImmutableMap.<Symbol, Type>builder()
            .put(C_BIGINT, BIGINT)
            .put(C_DOUBLE, DOUBLE)
            .put(C_VARCHAR, VARCHAR)
            .put(C_BOOLEAN, BOOLEAN)
            .put(C_BIGINT_1, BIGINT)
            .put(C_DOUBLE_1, DOUBLE)
            .put(C_VARCHAR_1, VARCHAR)
            .put(C_TIMESTAMP, TIMESTAMP)
            .put(C_DATE, DATE)
            .put(C_COLOR, COLOR) // Equatable, but not orderable
            .put(C_HYPER_LOG_LOG, HYPER_LOG_LOG) // Not Equatable or orderable
            .put(C_VARBINARY, VARBINARY)
            .put(C_DECIMAL_26_5, createDecimalType(26, 5))
            .put(C_DECIMAL_23_4, createDecimalType(23, 4))
            .put(C_INTEGER, INTEGER)
            .put(C_CHAR, createCharType(10))
            .put(C_DECIMAL_21_3, createDecimalType(21, 3))
            .put(C_DECIMAL_12_2, createDecimalType(12, 2))
            .put(C_DECIMAL_6_1, createDecimalType(6, 1))
            .put(C_DECIMAL_3_0, createDecimalType(3, 0))
            .put(C_DECIMAL_2_0, createDecimalType(2, 0))
            .put(C_SMALLINT, SMALLINT)
            .put(C_TINYINT, TINYINT)
            .put(C_REAL, REAL)
            .build());

    private static final long TIMESTAMP_VALUE = new DateTime(2013, 3, 30, 1, 5, 0, 0, DateTimeZone.UTC).getMillis();
    private static final long DATE_VALUE = TimeUnit.MILLISECONDS.toDays(new DateTime(2001, 1, 22, 0, 0, 0, 0, DateTimeZone.UTC).getMillis());
    private static final long COLOR_VALUE_1 = 1;
    private static final long COLOR_VALUE_2 = 2;

    private Metadata metadata;
    private LiteralEncoder literalEncoder;
    private DomainTranslator domainTranslator;

    @BeforeClass
    public void setup()
    {
        metadata = createTestMetadataManager();
        literalEncoder = new LiteralEncoder(metadata);
        domainTranslator = new DomainTranslator(metadata);
    }

    @AfterClass(alwaysRun = true)
    public void tearDown()
    {
        metadata = null;
        literalEncoder = null;
        domainTranslator = null;
    }

    @Test
    public void testNoneRoundTrip()
    {
        TupleDomain<Symbol> tupleDomain = TupleDomain.none();
        ExtractionResult result = fromPredicate(toPredicate(tupleDomain));
        assertEquals(result.getRemainingExpression(), TRUE_LITERAL);
        assertEquals(result.getTupleDomain(), tupleDomain);
    }

    @Test
    public void testAllRoundTrip()
    {
        TupleDomain<Symbol> tupleDomain = TupleDomain.all();
        ExtractionResult result = fromPredicate(toPredicate(tupleDomain));
        assertEquals(result.getRemainingExpression(), TRUE_LITERAL);
        assertEquals(result.getTupleDomain(), tupleDomain);
    }

    @Test
    public void testRoundTrip()
    {
        TupleDomain<Symbol> tupleDomain = withColumnDomains(ImmutableMap.<Symbol, Domain>builder()
                .put(C_BIGINT, Domain.singleValue(BIGINT, 1L))
                .put(C_DOUBLE, Domain.onlyNull(DOUBLE))
                .put(C_VARCHAR, Domain.notNull(VARCHAR))
                .put(C_BOOLEAN, Domain.singleValue(BOOLEAN, true))
                .put(C_BIGINT_1, Domain.singleValue(BIGINT, 2L))
                .put(C_DOUBLE_1, Domain.create(ValueSet.ofRanges(Range.lessThanOrEqual(DOUBLE, 1.1), Range.equal(DOUBLE, 2.0), Range.range(DOUBLE, 3.0, false, 3.5, true)), true))
                .put(C_VARCHAR_1, Domain.create(ValueSet.ofRanges(Range.lessThanOrEqual(VARCHAR, utf8Slice("2013-01-01")), Range.greaterThan(VARCHAR, utf8Slice("2013-10-01"))), false))
                .put(C_TIMESTAMP, Domain.singleValue(TIMESTAMP, TIMESTAMP_VALUE))
                .put(C_DATE, Domain.singleValue(DATE, DATE_VALUE))
                .put(C_COLOR, Domain.singleValue(COLOR, COLOR_VALUE_1))
                .put(C_HYPER_LOG_LOG, Domain.notNull(HYPER_LOG_LOG))
                .build());

        assertPredicateTranslates(toPredicate(tupleDomain), tupleDomain);
    }

    @Test
    public void testInOptimization()
    {
        Domain testDomain = Domain.create(
                ValueSet.all(BIGINT)
                        .subtract(ValueSet.ofRanges(
                                Range.equal(BIGINT, 1L), Range.equal(BIGINT, 2L), Range.equal(BIGINT, 3L))), false);

        TupleDomain<Symbol> tupleDomain = withColumnDomains(ImmutableMap.<Symbol, Domain>builder().put(C_BIGINT, testDomain).build());
        assertEquals(toPredicate(tupleDomain), or(
                lessThan(C_BIGINT, bigintLiteral(1L)),
                greaterThan(C_BIGINT, bigintLiteral(3L))));

        testDomain = Domain.create(
                ValueSet.ofRanges(
                        Range.lessThan(BIGINT, 4L)).intersect(
                        ValueSet.all(BIGINT)
                                .subtract(ValueSet.ofRanges(Range.equal(BIGINT, 1L), Range.equal(BIGINT, 2L), Range.equal(BIGINT, 3L)))), false);

        tupleDomain = withColumnDomains(ImmutableMap.<Symbol, Domain>builder().put(C_BIGINT, testDomain).build());
        assertEquals(toPredicate(tupleDomain), lessThan(C_BIGINT, bigintLiteral(1L)));

        testDomain = Domain.create(ValueSet.ofRanges(
                Range.range(BIGINT, 1L, true, 3L, true),
                Range.range(BIGINT, 5L, true, 7L, true),
                Range.range(BIGINT, 9L, true, 11L, true)),
                false);

        tupleDomain = withColumnDomains(ImmutableMap.<Symbol, Domain>builder().put(C_BIGINT, testDomain).build());
        assertEquals(toPredicate(tupleDomain),
                or(between(C_BIGINT, bigintLiteral(1L), bigintLiteral(3L)), (between(C_BIGINT, bigintLiteral(5L), bigintLiteral(7L))), (between(C_BIGINT, bigintLiteral(9L), bigintLiteral(11L)))));

        testDomain = Domain.create(
                ValueSet.ofRanges(
                        Range.lessThan(BIGINT, 4L))
                        .intersect(ValueSet.all(BIGINT)
                                .subtract(ValueSet.ofRanges(Range.equal(BIGINT, 1L), Range.equal(BIGINT, 2L), Range.equal(BIGINT, 3L))))
                        .union(ValueSet.ofRanges(Range.range(BIGINT, 7L, true, 9L, true))), false);

        tupleDomain = withColumnDomains(ImmutableMap.<Symbol, Domain>builder().put(C_BIGINT, testDomain).build());
        assertEquals(toPredicate(tupleDomain), or(
                lessThan(C_BIGINT, bigintLiteral(1L)),
                between(C_BIGINT, bigintLiteral(7L), bigintLiteral(9L))));

        testDomain = Domain.create(
                ValueSet.ofRanges(Range.lessThan(BIGINT, 4L))
                        .intersect(ValueSet.all(BIGINT)
                                .subtract(ValueSet.ofRanges(Range.equal(BIGINT, 1L), Range.equal(BIGINT, 2L), Range.equal(BIGINT, 3L))))
                        .union(ValueSet.ofRanges(Range.range(BIGINT, 7L, false, 9L, false), Range.range(BIGINT, 11L, false, 13L, false))), false);

        tupleDomain = withColumnDomains(ImmutableMap.<Symbol, Domain>builder().put(C_BIGINT, testDomain).build());
        assertEquals(toPredicate(tupleDomain), or(
                lessThan(C_BIGINT, bigintLiteral(1L)),
                and(greaterThan(C_BIGINT, bigintLiteral(7L)), lessThan(C_BIGINT, bigintLiteral(9L))),
                and(greaterThan(C_BIGINT, bigintLiteral(11L)), lessThan(C_BIGINT, bigintLiteral(13L)))));
    }

    @Test
    public void testToPredicateNone()
    {
        TupleDomain<Symbol> tupleDomain = withColumnDomains(ImmutableMap.<Symbol, Domain>builder()
                .put(C_BIGINT, Domain.singleValue(BIGINT, 1L))
                .put(C_DOUBLE, Domain.onlyNull(DOUBLE))
                .put(C_VARCHAR, Domain.notNull(VARCHAR))
                .put(C_BOOLEAN, Domain.none(BOOLEAN))
                .build());

        assertEquals(toPredicate(tupleDomain), FALSE_LITERAL);
    }

    @Test
    public void testToPredicateAllIgnored()
    {
        TupleDomain<Symbol> tupleDomain = withColumnDomains(ImmutableMap.<Symbol, Domain>builder()
                .put(C_BIGINT, Domain.singleValue(BIGINT, 1L))
                .put(C_DOUBLE, Domain.onlyNull(DOUBLE))
                .put(C_VARCHAR, Domain.notNull(VARCHAR))
                .put(C_BOOLEAN, Domain.all(BOOLEAN))
                .build());

        ExtractionResult result = fromPredicate(toPredicate(tupleDomain));
        assertEquals(result.getRemainingExpression(), TRUE_LITERAL);
        assertEquals(result.getTupleDomain(), withColumnDomains(ImmutableMap.<Symbol, Domain>builder()
                .put(C_BIGINT, Domain.singleValue(BIGINT, 1L))
                .put(C_DOUBLE, Domain.onlyNull(DOUBLE))
                .put(C_VARCHAR, Domain.notNull(VARCHAR))
                .build()));
    }

    @Test
    public void testToPredicate()
    {
        TupleDomain<Symbol> tupleDomain;

        tupleDomain = withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.notNull(BIGINT)));
        assertEquals(toPredicate(tupleDomain), isNotNull(C_BIGINT));

        tupleDomain = withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.onlyNull(BIGINT)));
        assertEquals(toPredicate(tupleDomain), isNull(C_BIGINT));

        tupleDomain = withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.none(BIGINT)));
        assertEquals(toPredicate(tupleDomain), FALSE_LITERAL);

        tupleDomain = withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.all(BIGINT)));
        assertEquals(toPredicate(tupleDomain), TRUE_LITERAL);

        tupleDomain = withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.greaterThan(BIGINT, 1L)), false)));
        assertEquals(toPredicate(tupleDomain), greaterThan(C_BIGINT, bigintLiteral(1L)));

        tupleDomain = withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.greaterThanOrEqual(BIGINT, 1L)), false)));
        assertEquals(toPredicate(tupleDomain), greaterThanOrEqual(C_BIGINT, bigintLiteral(1L)));

        tupleDomain = withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.lessThan(BIGINT, 1L)), false)));
        assertEquals(toPredicate(tupleDomain), lessThan(C_BIGINT, bigintLiteral(1L)));

        tupleDomain = withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.range(BIGINT, 0L, false, 1L, true)), false)));
        assertEquals(toPredicate(tupleDomain), and(greaterThan(C_BIGINT, bigintLiteral(0L)), lessThanOrEqual(C_BIGINT, bigintLiteral(1L))));

        tupleDomain = withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.lessThanOrEqual(BIGINT, 1L)), false)));
        assertEquals(toPredicate(tupleDomain), lessThanOrEqual(C_BIGINT, bigintLiteral(1L)));

        tupleDomain = withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.singleValue(BIGINT, 1L)));
        assertEquals(toPredicate(tupleDomain), equal(C_BIGINT, bigintLiteral(1L)));

        tupleDomain = withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.equal(BIGINT, 1L), Range.equal(BIGINT, 2L)), false)));
        assertEquals(toPredicate(tupleDomain), between(C_BIGINT, bigintLiteral(1L), bigintLiteral(2L)));

        tupleDomain = withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.lessThan(BIGINT, 1L)), true)));
        assertEquals(toPredicate(tupleDomain), or(lessThan(C_BIGINT, bigintLiteral(1L)), isNull(C_BIGINT)));

        tupleDomain = withColumnDomains(ImmutableMap.of(C_COLOR, Domain.create(ValueSet.of(COLOR, COLOR_VALUE_1), true)));
        assertEquals(toPredicate(tupleDomain), or(equal(C_COLOR, colorLiteral(COLOR_VALUE_1)), isNull(C_COLOR)));

        tupleDomain = withColumnDomains(ImmutableMap.of(C_COLOR, Domain.create(ValueSet.of(COLOR, COLOR_VALUE_1).complement(), true)));
        assertEquals(toPredicate(tupleDomain), or(not(equal(C_COLOR, colorLiteral(COLOR_VALUE_1))), isNull(C_COLOR)));

        tupleDomain = withColumnDomains(ImmutableMap.of(C_HYPER_LOG_LOG, Domain.onlyNull(HYPER_LOG_LOG)));
        assertEquals(toPredicate(tupleDomain), isNull(C_HYPER_LOG_LOG));

        tupleDomain = withColumnDomains(ImmutableMap.of(C_HYPER_LOG_LOG, Domain.notNull(HYPER_LOG_LOG)));
        assertEquals(toPredicate(tupleDomain), isNotNull(C_HYPER_LOG_LOG));
    }

    @Test
    public void testToPredicateWithRangeOptimisation()
    {
        TupleDomain<Symbol> tupleDomain;

        tupleDomain = withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.greaterThan(BIGINT, 1L), Range.lessThan(BIGINT, 1L)), false)));
        assertEquals(toPredicate(tupleDomain), notEqual(C_BIGINT, bigintLiteral(1L)));

        tupleDomain = withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(
                ValueSet.ofRanges(
                        Range.lessThan(BIGINT, 0L),
                        Range.range(BIGINT, 0L, false, 1L, false),
                        Range.greaterThan(BIGINT, 1L)),
                false)));
        assertEquals(toPredicate(tupleDomain), or(
                lessThan(C_BIGINT, bigintLiteral(0)),
                greaterThan(C_BIGINT, bigintLiteral(1))));

        tupleDomain = withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(
                ValueSet.ofRanges(
                        Range.lessThan(BIGINT, 0L),
                        Range.greaterThan(BIGINT, 2L)),
                false)));
        assertEquals(toPredicate(tupleDomain), or(
                lessThan(C_BIGINT, bigintLiteral(0L)),
                greaterThan(C_BIGINT, bigintLiteral(2L))));

        // floating point types: do not coalesce ranges when range "all" would be introduced
        tupleDomain = withColumnDomains(ImmutableMap.of(C_REAL, Domain.create(ValueSet.ofRanges(Range.greaterThan(REAL, 0L), Range.lessThan(REAL, 0L)), false)));
        assertEquals(toPredicate(tupleDomain), or(lessThan(C_REAL, realLiteral("0.0")), greaterThan(C_REAL, realLiteral("0.0"))));

        tupleDomain = withColumnDomains(ImmutableMap.of(C_REAL, Domain.create(
                ValueSet.ofRanges(
                        Range.lessThan(REAL, 0L),
                        Range.range(REAL, 0L, false, (long) Float.floatToIntBits(1F), false),
                        Range.greaterThan(REAL, (long) Float.floatToIntBits(1F))),
                false)));
        assertEquals(toPredicate(tupleDomain), or(
                lessThan(C_REAL, realLiteral("0.0")),
                and(greaterThan(C_REAL, realLiteral("0.0")), lessThan(C_REAL, realLiteral("1.0"))),
                greaterThan(C_REAL, realLiteral("1.0"))));

        tupleDomain = withColumnDomains(ImmutableMap.of(C_REAL, Domain.create(
                ValueSet.ofRanges(
                        Range.lessThan(REAL, 0L),
                        Range.range(REAL, 0L, false, (long) Float.floatToIntBits(1F), false),
                        Range.greaterThan(REAL, (long) Float.floatToIntBits(2F))),
                false)));
        assertEquals(toPredicate(tupleDomain), or(and(lessThan(C_REAL, realLiteral("1.0")), notEqual(C_REAL, realLiteral("0.0"))), greaterThan(C_REAL, realLiteral("2.0"))));

        tupleDomain = withColumnDomains(ImmutableMap.of(C_DOUBLE, Domain.create(
                ValueSet.ofRanges(
                        Range.lessThan(DOUBLE, 0.0),
                        Range.range(DOUBLE, 0.0, false, 1.0, false),
                        Range.range(DOUBLE, 2.0, false, 3.0, false),
                        Range.greaterThan(DOUBLE, 3.0)),
                false)));
        assertEquals(
                toPredicate(tupleDomain),
                or(
                        and(lessThan(C_DOUBLE, doubleLiteral(1)), notEqual(C_DOUBLE, doubleLiteral(0))),
                        and(greaterThan(C_DOUBLE, doubleLiteral(2)), notEqual(C_DOUBLE, doubleLiteral(3)))));
    }

    @Test
    public void testFromUnknownPredicate()
    {
        assertUnsupportedPredicate(unprocessableExpression1(C_BIGINT));
        assertUnsupportedPredicate(not(unprocessableExpression1(C_BIGINT)));
    }

    @Test
    public void testFromAndPredicate()
    {
        Expression originalPredicate = and(
                and(greaterThan(C_BIGINT, bigintLiteral(1L)), unprocessableExpression1(C_BIGINT)),
                and(lessThan(C_BIGINT, bigintLiteral(5L)), unprocessableExpression2(C_BIGINT)));
        ExtractionResult result = fromPredicate(originalPredicate);
        assertEquals(result.getRemainingExpression(), and(unprocessableExpression1(C_BIGINT), unprocessableExpression2(C_BIGINT)));
        assertEquals(result.getTupleDomain(), withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.range(BIGINT, 1L, false, 5L, false)), false))));

        // Test complements
        assertUnsupportedPredicate(not(and(
                and(greaterThan(C_BIGINT, bigintLiteral(1L)), unprocessableExpression1(C_BIGINT)),
                and(lessThan(C_BIGINT, bigintLiteral(5L)), unprocessableExpression2(C_BIGINT)))));

        originalPredicate = not(and(
                not(and(greaterThan(C_BIGINT, bigintLiteral(1L)), unprocessableExpression1(C_BIGINT))),
                not(and(lessThan(C_BIGINT, bigintLiteral(5L)), unprocessableExpression2(C_BIGINT)))));
        result = fromPredicate(originalPredicate);
        assertEquals(result.getRemainingExpression(), originalPredicate);
        assertEquals(result.getTupleDomain(), withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.notNull(BIGINT))));
    }

    @Test
    public void testFromOrPredicate()
    {
        Expression originalPredicate = or(
                and(greaterThan(C_BIGINT, bigintLiteral(1L)), unprocessableExpression1(C_BIGINT)),
                and(lessThan(C_BIGINT, bigintLiteral(5L)), unprocessableExpression2(C_BIGINT)));
        ExtractionResult result = fromPredicate(originalPredicate);
        assertEquals(result.getRemainingExpression(), originalPredicate);
        assertEquals(result.getTupleDomain(), withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.notNull(BIGINT))));

        originalPredicate = or(
                and(equal(C_BIGINT, bigintLiteral(1L)), unprocessableExpression1(C_BIGINT)),
                and(equal(C_BIGINT, bigintLiteral(2L)), unprocessableExpression2(C_BIGINT)));
        result = fromPredicate(originalPredicate);
        assertEquals(result.getRemainingExpression(), originalPredicate);
        assertEquals(result.getTupleDomain(), withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.equal(BIGINT, 1L), Range.equal(BIGINT, 2L)), false))));

        // Same unprocessableExpression means that we can do more extraction
        // If both sides are operating on the same single symbol
        originalPredicate = or(
                and(equal(C_BIGINT, bigintLiteral(1L)), unprocessableExpression1(C_BIGINT)),
                and(equal(C_BIGINT, bigintLiteral(2L)), unprocessableExpression1(C_BIGINT)));
        result = fromPredicate(originalPredicate);
        assertEquals(result.getRemainingExpression(), unprocessableExpression1(C_BIGINT));
        assertEquals(result.getTupleDomain(), withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.equal(BIGINT, 1L), Range.equal(BIGINT, 2L)), false))));

        // And not if they have different symbols
        assertUnsupportedPredicate(or(
                and(equal(C_BIGINT, bigintLiteral(1L)), unprocessableExpression1(C_BIGINT)),
                and(equal(C_DOUBLE, doubleLiteral(2.0)), unprocessableExpression1(C_BIGINT))));

        // Domain union implicitly adds NaN as an accepted value
        // The original predicate is returned as the RemainingExpression
        // (even if left and right unprocessableExpressions are the same)
        originalPredicate = or(
                greaterThan(C_DOUBLE, doubleLiteral(2.0)),
                lessThan(C_DOUBLE, doubleLiteral(5.0)));
        result = fromPredicate(originalPredicate);
        assertEquals(result.getRemainingExpression(), originalPredicate);
        assertEquals(result.getTupleDomain(), withColumnDomains(ImmutableMap.of(C_DOUBLE, Domain.notNull(DOUBLE))));

        originalPredicate = or(
                greaterThan(C_REAL, realLiteral("2.0")),
                lessThan(C_REAL, realLiteral("5.0")),
                isNull(C_REAL));
        result = fromPredicate(originalPredicate);
        assertEquals(result.getRemainingExpression(), originalPredicate);
        assertEquals(result.getTupleDomain(), TupleDomain.all());

        originalPredicate = or(
                and(greaterThan(C_DOUBLE, doubleLiteral(2.0)), unprocessableExpression1(C_DOUBLE)),
                and(lessThan(C_DOUBLE, doubleLiteral(5.0)), unprocessableExpression1(C_DOUBLE)));
        result = fromPredicate(originalPredicate);
        assertEquals(result.getRemainingExpression(), originalPredicate);
        assertEquals(result.getTupleDomain(), withColumnDomains(ImmutableMap.of(C_DOUBLE, Domain.notNull(DOUBLE))));

        originalPredicate = or(
                and(greaterThan(C_REAL, realLiteral("2.0")), unprocessableExpression1(C_REAL)),
                and(lessThan(C_REAL, realLiteral("5.0")), unprocessableExpression1(C_REAL)));
        result = fromPredicate(originalPredicate);
        assertEquals(result.getRemainingExpression(), originalPredicate);
        assertEquals(result.getTupleDomain(), withColumnDomains(ImmutableMap.of(C_REAL, Domain.notNull(REAL))));

        // We can make another optimization if one side is the super set of the other side
        originalPredicate = or(
                and(greaterThan(C_BIGINT, bigintLiteral(1L)), greaterThan(C_DOUBLE, doubleLiteral(1.0)), unprocessableExpression1(C_BIGINT)),
                and(greaterThan(C_BIGINT, bigintLiteral(2L)), greaterThan(C_DOUBLE, doubleLiteral(2.0)), unprocessableExpression1(C_BIGINT)));
        result = fromPredicate(originalPredicate);
        assertEquals(result.getRemainingExpression(), unprocessableExpression1(C_BIGINT));
        assertEquals(result.getTupleDomain(), withColumnDomains(ImmutableMap.of(
                C_BIGINT, Domain.create(ValueSet.ofRanges(Range.greaterThan(BIGINT, 1L)), false),
                C_DOUBLE, Domain.create(ValueSet.ofRanges(Range.greaterThan(DOUBLE, 1.0)), false))));

        // We can't make those inferences if the unprocessableExpressions are non-deterministic
        originalPredicate = or(
                and(equal(C_BIGINT, bigintLiteral(1L)), randPredicate(C_BIGINT, BIGINT)),
                and(equal(C_BIGINT, bigintLiteral(2L)), randPredicate(C_BIGINT, BIGINT)));
        result = fromPredicate(originalPredicate);
        assertEquals(result.getRemainingExpression(), originalPredicate);
        assertEquals(result.getTupleDomain(), withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.equal(BIGINT, 1L), Range.equal(BIGINT, 2L)), false))));

        // Test complements
        originalPredicate = not(or(
                and(greaterThan(C_BIGINT, bigintLiteral(1L)), unprocessableExpression1(C_BIGINT)),
                and(lessThan(C_BIGINT, bigintLiteral(5L)), unprocessableExpression2(C_BIGINT))));
        result = fromPredicate(originalPredicate);
        assertEquals(result.getRemainingExpression(), and(
                not(and(greaterThan(C_BIGINT, bigintLiteral(1L)), unprocessableExpression1(C_BIGINT))),
                not(and(lessThan(C_BIGINT, bigintLiteral(5L)), unprocessableExpression2(C_BIGINT)))));
        assertTrue(result.getTupleDomain().isAll());

        originalPredicate = not(or(
                not(and(greaterThan(C_BIGINT, bigintLiteral(1L)), unprocessableExpression1(C_BIGINT))),
                not(and(lessThan(C_BIGINT, bigintLiteral(5L)), unprocessableExpression2(C_BIGINT)))));
        result = fromPredicate(originalPredicate);
        assertEquals(result.getRemainingExpression(), and(unprocessableExpression1(C_BIGINT), unprocessableExpression2(C_BIGINT)));
        assertEquals(result.getTupleDomain(), withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.range(BIGINT, 1L, false, 5L, false)), false))));
    }

    @Test
    public void testFromNotPredicate()
    {
        assertUnsupportedPredicate(not(and(equal(C_BIGINT, bigintLiteral(1L)), unprocessableExpression1(C_BIGINT))));
        assertUnsupportedPredicate(not(unprocessableExpression1(C_BIGINT)));

        assertPredicateIsAlwaysFalse(not(TRUE_LITERAL));

        assertPredicateTranslates(
                not(equal(C_BIGINT, bigintLiteral(1L))),
                withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.lessThan(BIGINT, 1L), Range.greaterThan(BIGINT, 1L)), false))));
    }

    @Test
    public void testFromUnprocessableComparison()
    {
        assertUnsupportedPredicate(comparison(GREATER_THAN, unprocessableExpression1(C_BIGINT), unprocessableExpression2(C_BIGINT)));
        assertUnsupportedPredicate(not(comparison(GREATER_THAN, unprocessableExpression1(C_BIGINT), unprocessableExpression2(C_BIGINT))));
    }

    @Test
    public void testFromBasicComparisons()
    {
        // Test out the extraction of all basic comparisons
        assertPredicateTranslates(
                greaterThan(C_BIGINT, bigintLiteral(2L)),
                withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.greaterThan(BIGINT, 2L)), false))));

        assertPredicateTranslates(
                greaterThanOrEqual(C_BIGINT, bigintLiteral(2L)),
                withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.greaterThanOrEqual(BIGINT, 2L)), false))));

        assertPredicateTranslates(
                lessThan(C_BIGINT, bigintLiteral(2L)),
                withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.lessThan(BIGINT, 2L)), false))));

        assertPredicateTranslates(
                lessThanOrEqual(C_BIGINT, bigintLiteral(2L)),
                withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.lessThanOrEqual(BIGINT, 2L)), false))));

        assertPredicateTranslates(
                equal(C_BIGINT, bigintLiteral(2L)),
                withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.equal(BIGINT, 2L)), false))));

        assertPredicateTranslates(
                notEqual(C_BIGINT, bigintLiteral(2L)),
                withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.lessThan(BIGINT, 2L), Range.greaterThan(BIGINT, 2L)), false))));

        assertPredicateTranslates(
                isDistinctFrom(C_BIGINT, bigintLiteral(2L)),
                withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.lessThan(BIGINT, 2L), Range.greaterThan(BIGINT, 2L)), true))));

        assertPredicateTranslates(
                equal(C_COLOR, colorLiteral(COLOR_VALUE_1)),
                withColumnDomains(ImmutableMap.of(C_COLOR, Domain.create(ValueSet.of(COLOR, COLOR_VALUE_1), false))));

        assertPredicateTranslates(
                in(C_COLOR, ImmutableList.of(colorLiteral(COLOR_VALUE_1), colorLiteral(COLOR_VALUE_2))),
                withColumnDomains(ImmutableMap.of(C_COLOR, Domain.create(ValueSet.of(COLOR, COLOR_VALUE_1, COLOR_VALUE_2), false))));

        assertPredicateTranslates(
                isDistinctFrom(C_COLOR, colorLiteral(COLOR_VALUE_1)),
                withColumnDomains(ImmutableMap.of(C_COLOR, Domain.create(ValueSet.of(COLOR, COLOR_VALUE_1).complement(), true))));

        // Test complement
        assertPredicateTranslates(
                not(greaterThan(C_BIGINT, bigintLiteral(2L))),
                withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.lessThanOrEqual(BIGINT, 2L)), false))));

        assertPredicateTranslates(
                not(greaterThanOrEqual(C_BIGINT, bigintLiteral(2L))),
                withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.lessThan(BIGINT, 2L)), false))));

        assertPredicateTranslates(
                not(lessThan(C_BIGINT, bigintLiteral(2L))),
                withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.greaterThanOrEqual(BIGINT, 2L)), false))));

        assertPredicateTranslates(
                not(lessThanOrEqual(C_BIGINT, bigintLiteral(2L))),
                withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.greaterThan(BIGINT, 2L)), false))));

        assertPredicateTranslates(
                not(equal(C_BIGINT, bigintLiteral(2L))),
                withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.lessThan(BIGINT, 2L), Range.greaterThan(BIGINT, 2L)), false))));

        assertPredicateTranslates(
                not(notEqual(C_BIGINT, bigintLiteral(2L))),
                withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.equal(BIGINT, 2L)), false))));

        assertPredicateTranslates(
                not(isDistinctFrom(C_BIGINT, bigintLiteral(2L))),
                withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.equal(BIGINT, 2L)), false))));

        assertPredicateTranslates(
                not(equal(C_COLOR, colorLiteral(COLOR_VALUE_1))),
                withColumnDomains(ImmutableMap.of(C_COLOR, Domain.create(ValueSet.of(COLOR, COLOR_VALUE_1).complement(), false))));

        assertPredicateTranslates(
                not(in(C_COLOR, ImmutableList.of(colorLiteral(COLOR_VALUE_1), colorLiteral(COLOR_VALUE_2)))),
                withColumnDomains(ImmutableMap.of(C_COLOR, Domain.create(ValueSet.of(COLOR, COLOR_VALUE_1, COLOR_VALUE_2).complement(), false))));

        assertPredicateTranslates(
                not(isDistinctFrom(C_COLOR, colorLiteral(COLOR_VALUE_1))),
                withColumnDomains(ImmutableMap.of(C_COLOR, Domain.create(ValueSet.of(COLOR, COLOR_VALUE_1), false))));
    }

    @Test
    public void testFromFlippedBasicComparisons()
    {
        // Test out the extraction of all basic comparisons where the reference literal ordering is flipped
        assertPredicateTranslates(
                comparison(GREATER_THAN, bigintLiteral(2L), C_BIGINT.toSymbolReference()),
                withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.lessThan(BIGINT, 2L)), false))));

        assertPredicateTranslates(
                comparison(GREATER_THAN_OR_EQUAL, bigintLiteral(2L), C_BIGINT.toSymbolReference()),
                withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.lessThanOrEqual(BIGINT, 2L)), false))));

        assertPredicateTranslates(
                comparison(LESS_THAN, bigintLiteral(2L), C_BIGINT.toSymbolReference()),
                withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.greaterThan(BIGINT, 2L)), false))));

        assertPredicateTranslates(
                comparison(LESS_THAN_OR_EQUAL, bigintLiteral(2L), C_BIGINT.toSymbolReference()),
                withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.greaterThanOrEqual(BIGINT, 2L)), false))));

        assertPredicateTranslates(comparison(EQUAL, bigintLiteral(2L), C_BIGINT.toSymbolReference()),
                withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.equal(BIGINT, 2L)), false))));

        assertPredicateTranslates(comparison(EQUAL, colorLiteral(COLOR_VALUE_1), C_COLOR.toSymbolReference()),
                withColumnDomains(ImmutableMap.of(C_COLOR, Domain.create(ValueSet.of(COLOR, COLOR_VALUE_1), false))));

        assertPredicateTranslates(comparison(NOT_EQUAL, bigintLiteral(2L), C_BIGINT.toSymbolReference()),
                withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.lessThan(BIGINT, 2L), Range.greaterThan(BIGINT, 2L)), false))));

        assertPredicateTranslates(
                comparison(NOT_EQUAL, colorLiteral(COLOR_VALUE_1), C_COLOR.toSymbolReference()),
                withColumnDomains(ImmutableMap.of(C_COLOR, Domain.create(ValueSet.of(COLOR, COLOR_VALUE_1).complement(), false))));

        assertPredicateTranslates(comparison(IS_DISTINCT_FROM, bigintLiteral(2L), C_BIGINT.toSymbolReference()),
                withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.lessThan(BIGINT, 2L), Range.greaterThan(BIGINT, 2L)), true))));

        assertPredicateTranslates(
                comparison(IS_DISTINCT_FROM, colorLiteral(COLOR_VALUE_1), C_COLOR.toSymbolReference()),
                withColumnDomains(ImmutableMap.of(C_COLOR, Domain.create(ValueSet.of(COLOR, COLOR_VALUE_1).complement(), true))));

        assertPredicateTranslates(
                comparison(IS_DISTINCT_FROM, nullLiteral(BIGINT), C_BIGINT.toSymbolReference()),
                withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.notNull(BIGINT))));
    }

    @Test
    public void testFromBasicComparisonsWithNulls()
    {
        // Test out the extraction of all basic comparisons with null literals
        assertPredicateIsAlwaysFalse(greaterThan(C_BIGINT, nullLiteral(BIGINT)));

        assertPredicateTranslates(
                greaterThan(C_VARCHAR, nullLiteral(VARCHAR)),
                withColumnDomains(ImmutableMap.of(C_VARCHAR, Domain.create(ValueSet.none(VARCHAR), false))));

        assertPredicateIsAlwaysFalse(greaterThanOrEqual(C_BIGINT, nullLiteral(BIGINT)));
        assertPredicateIsAlwaysFalse(lessThan(C_BIGINT, nullLiteral(BIGINT)));
        assertPredicateIsAlwaysFalse(lessThanOrEqual(C_BIGINT, nullLiteral(BIGINT)));
        assertPredicateIsAlwaysFalse(equal(C_BIGINT, nullLiteral(BIGINT)));
        assertPredicateIsAlwaysFalse(equal(C_COLOR, nullLiteral(COLOR)));
        assertPredicateIsAlwaysFalse(notEqual(C_BIGINT, nullLiteral(BIGINT)));
        assertPredicateIsAlwaysFalse(notEqual(C_COLOR, nullLiteral(COLOR)));

        assertPredicateTranslates(
                isDistinctFrom(C_BIGINT, nullLiteral(BIGINT)),
                withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.notNull(BIGINT))));

        assertPredicateTranslates(
                isDistinctFrom(C_COLOR, nullLiteral(COLOR)),
                withColumnDomains(ImmutableMap.of(C_COLOR, Domain.notNull(COLOR))));

        // Test complements
        assertPredicateIsAlwaysFalse(not(greaterThan(C_BIGINT, nullLiteral(BIGINT))));
        assertPredicateIsAlwaysFalse(not(greaterThanOrEqual(C_BIGINT, nullLiteral(BIGINT))));
        assertPredicateIsAlwaysFalse(not(lessThan(C_BIGINT, nullLiteral(BIGINT))));
        assertPredicateIsAlwaysFalse(not(lessThanOrEqual(C_BIGINT, nullLiteral(BIGINT))));
        assertPredicateIsAlwaysFalse(not(equal(C_BIGINT, nullLiteral(BIGINT))));
        assertPredicateIsAlwaysFalse(not(equal(C_COLOR, nullLiteral(COLOR))));
        assertPredicateIsAlwaysFalse(not(notEqual(C_BIGINT, nullLiteral(BIGINT))));
        assertPredicateIsAlwaysFalse(not(notEqual(C_COLOR, nullLiteral(COLOR))));

        assertPredicateTranslates(
                not(isDistinctFrom(C_BIGINT, nullLiteral(BIGINT))),
                withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.onlyNull(BIGINT))));

        assertPredicateTranslates(
                not(isDistinctFrom(C_COLOR, nullLiteral(COLOR))),
                withColumnDomains(ImmutableMap.of(C_COLOR, Domain.onlyNull(COLOR))));
    }

    @Test
    public void testFromBasicComparisonsWithNaN()
    {
        Expression nanDouble = literalEncoder.toExpression(Double.NaN, DOUBLE);

        assertPredicateIsAlwaysFalse(equal(C_DOUBLE, nanDouble));
        assertPredicateIsAlwaysFalse(greaterThan(C_DOUBLE, nanDouble));
        assertPredicateIsAlwaysFalse(greaterThanOrEqual(C_DOUBLE, nanDouble));
        assertPredicateIsAlwaysFalse(lessThan(C_DOUBLE, nanDouble));
        assertPredicateIsAlwaysFalse(lessThanOrEqual(C_DOUBLE, nanDouble));
        assertPredicateTranslates(notEqual(C_DOUBLE, nanDouble), TupleDomain.withColumnDomains(ImmutableMap.of(C_DOUBLE, Domain.notNull(DOUBLE))));
        assertUnsupportedPredicate(isDistinctFrom(C_DOUBLE, nanDouble));

        assertPredicateTranslates(not(equal(C_DOUBLE, nanDouble)), TupleDomain.withColumnDomains(ImmutableMap.of(C_DOUBLE, Domain.notNull(DOUBLE))));
        assertPredicateTranslates(not(greaterThan(C_DOUBLE, nanDouble)), TupleDomain.withColumnDomains(ImmutableMap.of(C_DOUBLE, Domain.notNull(DOUBLE))));
        assertPredicateTranslates(not(greaterThanOrEqual(C_DOUBLE, nanDouble)), TupleDomain.withColumnDomains(ImmutableMap.of(C_DOUBLE, Domain.notNull(DOUBLE))));
        assertPredicateTranslates(not(lessThan(C_DOUBLE, nanDouble)), TupleDomain.withColumnDomains(ImmutableMap.of(C_DOUBLE, Domain.notNull(DOUBLE))));
        assertPredicateTranslates(not(lessThanOrEqual(C_DOUBLE, nanDouble)), TupleDomain.withColumnDomains(ImmutableMap.of(C_DOUBLE, Domain.notNull(DOUBLE))));
        assertPredicateIsAlwaysFalse(not(notEqual(C_DOUBLE, nanDouble)));
        assertUnsupportedPredicate(not(isDistinctFrom(C_DOUBLE, nanDouble)));

        Expression nanReal = literalEncoder.toExpression((long) Float.floatToIntBits(Float.NaN), REAL);

        assertPredicateIsAlwaysFalse(equal(C_REAL, nanReal));
        assertPredicateIsAlwaysFalse(greaterThan(C_REAL, nanReal));
        assertPredicateIsAlwaysFalse(greaterThanOrEqual(C_REAL, nanReal));
        assertPredicateIsAlwaysFalse(lessThan(C_REAL, nanReal));
        assertPredicateIsAlwaysFalse(lessThanOrEqual(C_REAL, nanReal));
        assertPredicateTranslates(notEqual(C_REAL, nanReal), TupleDomain.withColumnDomains(ImmutableMap.of(C_REAL, Domain.notNull(REAL))));
        assertUnsupportedPredicate(isDistinctFrom(C_REAL, nanReal));

        assertPredicateTranslates(not(equal(C_REAL, nanReal)), TupleDomain.withColumnDomains(ImmutableMap.of(C_REAL, Domain.notNull(REAL))));
        assertPredicateTranslates(not(greaterThan(C_REAL, nanReal)), TupleDomain.withColumnDomains(ImmutableMap.of(C_REAL, Domain.notNull(REAL))));
        assertPredicateTranslates(not(greaterThanOrEqual(C_REAL, nanReal)), TupleDomain.withColumnDomains(ImmutableMap.of(C_REAL, Domain.notNull(REAL))));
        assertPredicateTranslates(not(lessThan(C_REAL, nanReal)), TupleDomain.withColumnDomains(ImmutableMap.of(C_REAL, Domain.notNull(REAL))));
        assertPredicateTranslates(not(lessThanOrEqual(C_REAL, nanReal)), TupleDomain.withColumnDomains(ImmutableMap.of(C_REAL, Domain.notNull(REAL))));
        assertPredicateIsAlwaysFalse(not(notEqual(C_REAL, nanReal)));
        assertUnsupportedPredicate(not(isDistinctFrom(C_REAL, nanReal)));
    }

    @Test
    public void testNonImplicitCastOnSymbolSide()
    {
        // we expect TupleDomain.all here().
        // see comment in DomainTranslator.Visitor.visitComparisonExpression()
        assertUnsupportedPredicate(equal(
                new Cast(C_TIMESTAMP.toSymbolReference(), toSqlType(DATE)),
                toExpression(DATE_VALUE, DATE)));
        assertUnsupportedPredicate(equal(
                new Cast(C_DECIMAL_12_2.toSymbolReference(), toSqlType(BIGINT)),
                bigintLiteral(135L)));
    }

    @Test
    public void testNoSaturatedFloorCastFromUnsupportedApproximateDomain()
    {
        assertUnsupportedPredicate(equal(
                new Cast(C_DECIMAL_12_2.toSymbolReference(), toSqlType(DOUBLE)),
                toExpression(12345.56, DOUBLE)));

        assertUnsupportedPredicate(equal(
                new Cast(C_BIGINT.toSymbolReference(), toSqlType(DOUBLE)),
                toExpression(12345.56, DOUBLE)));

        assertUnsupportedPredicate(equal(
                new Cast(C_BIGINT.toSymbolReference(), toSqlType(REAL)),
                toExpression(realValue(12345.56f), REAL)));

        assertUnsupportedPredicate(equal(
                new Cast(C_INTEGER.toSymbolReference(), toSqlType(REAL)),
                toExpression(realValue(12345.56f), REAL)));
    }

    @Test
    public void testFromComparisonsWithCoercions()
    {
        // B is a double column. Check that it can be compared against longs
        assertPredicateTranslates(
                greaterThan(C_DOUBLE, cast(bigintLiteral(2L), DOUBLE)),
                withColumnDomains(ImmutableMap.of(C_DOUBLE, Domain.create(ValueSet.ofRanges(Range.greaterThan(DOUBLE, 2.0)), false))));

        // C is a string column. Check that it can be compared.
        assertPredicateTranslates(
                greaterThan(C_VARCHAR, stringLiteral("test", VARCHAR)),
                withColumnDomains(ImmutableMap.of(C_VARCHAR, Domain.create(ValueSet.ofRanges(Range.greaterThan(VARCHAR, utf8Slice("test"))), false))));

        // A is a integer column. Check that it can be compared against doubles
        assertPredicateTranslates(
                greaterThan(cast(C_INTEGER, DOUBLE), doubleLiteral(2.0)),
                withColumnDomains(ImmutableMap.of(C_INTEGER, Domain.create(ValueSet.ofRanges(Range.greaterThan(INTEGER, 2L)), false))));

        assertPredicateTranslates(
                greaterThan(cast(C_INTEGER, DOUBLE), doubleLiteral(2.1)),
                withColumnDomains(ImmutableMap.of(C_INTEGER, Domain.create(ValueSet.ofRanges(Range.greaterThan(INTEGER, 2L)), false))));

        assertPredicateTranslates(
                greaterThanOrEqual(cast(C_INTEGER, DOUBLE), doubleLiteral(2.0)),
                withColumnDomains(ImmutableMap.of(C_INTEGER, Domain.create(ValueSet.ofRanges(Range.greaterThanOrEqual(INTEGER, 2L)), false))));

        assertPredicateTranslates(
                greaterThanOrEqual(cast(C_INTEGER, DOUBLE), doubleLiteral(2.1)),
                withColumnDomains(ImmutableMap.of(C_INTEGER, Domain.create(ValueSet.ofRanges(Range.greaterThan(INTEGER, 2L)), false))));

        assertPredicateTranslates(
                lessThan(cast(C_INTEGER, DOUBLE), doubleLiteral(2.0)),
                withColumnDomains(ImmutableMap.of(C_INTEGER, Domain.create(ValueSet.ofRanges(Range.lessThan(INTEGER, 2L)), false))));

        assertPredicateTranslates(
                lessThan(cast(C_INTEGER, DOUBLE), doubleLiteral(2.1)),
                withColumnDomains(ImmutableMap.of(C_INTEGER, Domain.create(ValueSet.ofRanges(Range.lessThanOrEqual(INTEGER, 2L)), false))));

        assertPredicateTranslates(
                lessThanOrEqual(cast(C_INTEGER, DOUBLE), doubleLiteral(2.0)),
                withColumnDomains(ImmutableMap.of(C_INTEGER, Domain.create(ValueSet.ofRanges(Range.lessThanOrEqual(INTEGER, 2L)), false))));

        assertPredicateTranslates(
                lessThanOrEqual(cast(C_INTEGER, DOUBLE), doubleLiteral(2.1)),
                withColumnDomains(ImmutableMap.of(C_INTEGER, Domain.create(ValueSet.ofRanges(Range.lessThanOrEqual(INTEGER, 2L)), false))));

        assertPredicateTranslates(
                equal(cast(C_INTEGER, DOUBLE), doubleLiteral(2.0)),
                withColumnDomains(ImmutableMap.of(C_INTEGER, Domain.create(ValueSet.ofRanges(Range.equal(INTEGER, 2L)), false))));

        assertPredicateTranslates(
                equal(cast(C_INTEGER, DOUBLE), doubleLiteral(2.1)),
                withColumnDomains(ImmutableMap.of(C_INTEGER, Domain.none(INTEGER))));

        assertPredicateTranslates(
                notEqual(cast(C_INTEGER, DOUBLE), doubleLiteral(2.0)),
                withColumnDomains(ImmutableMap.of(C_INTEGER, Domain.create(ValueSet.ofRanges(Range.lessThan(INTEGER, 2L), Range.greaterThan(INTEGER, 2L)), false))));

        assertPredicateTranslates(
                notEqual(cast(C_INTEGER, DOUBLE), doubleLiteral(2.1)),
                withColumnDomains(ImmutableMap.of(C_INTEGER, Domain.notNull(INTEGER))));

        assertPredicateTranslates(
                isDistinctFrom(cast(C_INTEGER, DOUBLE), doubleLiteral(2.0)),
                withColumnDomains(ImmutableMap.of(C_INTEGER, Domain.create(ValueSet.ofRanges(Range.lessThan(INTEGER, 2L), Range.greaterThan(INTEGER, 2L)), true))));

        assertPredicateIsAlwaysTrue(isDistinctFrom(cast(C_INTEGER, DOUBLE), doubleLiteral(2.1)));

        // Test complements

        // B is a double column. Check that it can be compared against longs
        assertPredicateTranslates(
                greaterThan(C_DOUBLE, cast(bigintLiteral(2L), DOUBLE)),
                withColumnDomains(ImmutableMap.of(C_DOUBLE, Domain.create(ValueSet.ofRanges(Range.greaterThan(DOUBLE, 2.0)), false))));

        // C is a string column. Check that it can be compared.
        assertPredicateTranslates(
                not(greaterThan(C_VARCHAR, stringLiteral("test", VARCHAR))),
                withColumnDomains(ImmutableMap.of(C_VARCHAR, Domain.create(ValueSet.ofRanges(Range.lessThanOrEqual(VARCHAR, utf8Slice("test"))), false))));

        // A is a integer column. Check that it can be compared against doubles
        assertPredicateTranslates(
                not(greaterThan(cast(C_INTEGER, DOUBLE), doubleLiteral(2.0))),
                withColumnDomains(ImmutableMap.of(C_INTEGER, Domain.create(ValueSet.ofRanges(Range.lessThanOrEqual(INTEGER, 2L)), false))));

        assertPredicateTranslates(
                not(greaterThan(cast(C_INTEGER, DOUBLE), doubleLiteral(2.1))),
                withColumnDomains(ImmutableMap.of(C_INTEGER, Domain.create(ValueSet.ofRanges(Range.lessThanOrEqual(INTEGER, 2L)), false))));

        assertPredicateTranslates(
                not(greaterThanOrEqual(cast(C_INTEGER, DOUBLE), doubleLiteral(2.0))),
                withColumnDomains(ImmutableMap.of(C_INTEGER, Domain.create(ValueSet.ofRanges(Range.lessThan(INTEGER, 2L)), false))));

        assertPredicateTranslates(
                not(greaterThanOrEqual(cast(C_INTEGER, DOUBLE), doubleLiteral(2.1))),
                withColumnDomains(ImmutableMap.of(C_INTEGER, Domain.create(ValueSet.ofRanges(Range.lessThanOrEqual(INTEGER, 2L)), false))));

        assertPredicateTranslates(
                not(lessThan(cast(C_INTEGER, DOUBLE), doubleLiteral(2.0))),
                withColumnDomains(ImmutableMap.of(C_INTEGER, Domain.create(ValueSet.ofRanges(Range.greaterThanOrEqual(INTEGER, 2L)), false))));

        assertPredicateTranslates(
                not(lessThan(cast(C_INTEGER, DOUBLE), doubleLiteral(2.1))),
                withColumnDomains(ImmutableMap.of(C_INTEGER, Domain.create(ValueSet.ofRanges(Range.greaterThan(INTEGER, 2L)), false))));

        assertPredicateTranslates(
                not(lessThanOrEqual(cast(C_INTEGER, DOUBLE), doubleLiteral(2.0))),
                withColumnDomains(ImmutableMap.of(C_INTEGER, Domain.create(ValueSet.ofRanges(Range.greaterThan(INTEGER, 2L)), false))));

        assertPredicateTranslates(
                not(lessThanOrEqual(cast(C_INTEGER, DOUBLE), doubleLiteral(2.1))),
                withColumnDomains(ImmutableMap.of(C_INTEGER, Domain.create(ValueSet.ofRanges(Range.greaterThan(INTEGER, 2L)), false))));

        assertPredicateTranslates(
                not(equal(cast(C_INTEGER, DOUBLE), doubleLiteral(2.0))),
                withColumnDomains(ImmutableMap.of(C_INTEGER, Domain.create(ValueSet.ofRanges(Range.lessThan(INTEGER, 2L), Range.greaterThan(INTEGER, 2L)), false))));

        assertPredicateTranslates(
                not(equal(cast(C_INTEGER, DOUBLE), doubleLiteral(2.1))),
                withColumnDomains(ImmutableMap.of(C_INTEGER, Domain.notNull(INTEGER))));

        assertPredicateTranslates(
                not(notEqual(cast(C_INTEGER, DOUBLE), doubleLiteral(2.0))),
                withColumnDomains(ImmutableMap.of(C_INTEGER, Domain.create(ValueSet.ofRanges(Range.equal(INTEGER, 2L)), false))));

        assertPredicateTranslates(
                not(notEqual(cast(C_INTEGER, DOUBLE), doubleLiteral(2.1))),
                withColumnDomains(ImmutableMap.of(C_INTEGER, Domain.none(INTEGER))));

        assertPredicateTranslates(
                not(isDistinctFrom(cast(C_INTEGER, DOUBLE), doubleLiteral(2.0))),
                withColumnDomains(ImmutableMap.of(C_INTEGER, Domain.create(ValueSet.ofRanges(Range.equal(INTEGER, 2L)), false))));

        assertPredicateIsAlwaysFalse(not(isDistinctFrom(cast(C_INTEGER, DOUBLE), doubleLiteral(2.1))));
    }

    @Test
    public void testFromUnprocessableInPredicate()
    {
        assertUnsupportedPredicate(new InPredicate(unprocessableExpression1(C_BIGINT), new InListExpression(ImmutableList.of(TRUE_LITERAL))));
        assertUnsupportedPredicate(new InPredicate(C_BOOLEAN.toSymbolReference(), new InListExpression(ImmutableList.of(unprocessableExpression1(C_BOOLEAN)))));
        assertUnsupportedPredicate(
                new InPredicate(C_BOOLEAN.toSymbolReference(), new InListExpression(ImmutableList.of(TRUE_LITERAL, unprocessableExpression1(C_BOOLEAN)))));
        assertUnsupportedPredicate(not(new InPredicate(C_BOOLEAN.toSymbolReference(), new InListExpression(ImmutableList.of(unprocessableExpression1(C_BOOLEAN))))));
    }

    @Test
    public void testFromInPredicate()
    {
        assertPredicateTranslates(
                in(C_BIGINT, ImmutableList.of(1L)),
                withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.singleValue(BIGINT, 1L))));

        assertPredicateTranslates(
                in(C_COLOR, ImmutableList.of(colorLiteral(COLOR_VALUE_1))),
                withColumnDomains(ImmutableMap.of(C_COLOR, Domain.singleValue(COLOR, COLOR_VALUE_1))));

        assertPredicateTranslates(
                in(C_BIGINT, ImmutableList.of(1L, 2L)),
                withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.equal(BIGINT, 1L), Range.equal(BIGINT, 2L)), false))));

        assertPredicateTranslates(
                in(C_COLOR, ImmutableList.of(colorLiteral(COLOR_VALUE_1), colorLiteral(COLOR_VALUE_2))),
                withColumnDomains(ImmutableMap.of(C_COLOR, Domain.create(ValueSet.of(COLOR, COLOR_VALUE_1, COLOR_VALUE_2), false))));

        assertPredicateTranslates(
                not(in(C_BIGINT, ImmutableList.of(1L, 2L))),
                withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.lessThan(BIGINT, 1L), Range.range(BIGINT, 1L, false, 2L, false), Range.greaterThan(BIGINT, 2L)), false))));

        assertPredicateTranslates(
                not(in(C_COLOR, ImmutableList.of(colorLiteral(COLOR_VALUE_1), colorLiteral(COLOR_VALUE_2)))),
                withColumnDomains(ImmutableMap.of(C_COLOR, Domain.create(ValueSet.of(COLOR, COLOR_VALUE_1, COLOR_VALUE_2).complement(), false))));
    }

    @Test
    public void testInPredicateWithNull()
    {
        assertPredicateTranslates(
                in(C_BIGINT, Arrays.asList(1L, 2L, null)),
                withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.equal(BIGINT, 1L), Range.equal(BIGINT, 2L)), false))));

        assertPredicateIsAlwaysFalse(not(in(C_BIGINT, Arrays.asList(1L, 2L, null))));
        assertPredicateIsAlwaysFalse(in(C_BIGINT, Arrays.asList(new Long[] {null})));
        assertPredicateIsAlwaysFalse(not(in(C_BIGINT, Arrays.asList(new Long[] {null}))));

        assertUnsupportedPredicate(isNull(in(C_BIGINT, Arrays.asList(1L, 2L, null))));
        assertUnsupportedPredicate(isNotNull(in(C_BIGINT, Arrays.asList(1L, 2L, null))));
        assertUnsupportedPredicate(isNull(in(C_BIGINT, Arrays.asList(new Long[] {null}))));
        assertUnsupportedPredicate(isNotNull(in(C_BIGINT, Arrays.asList(new Long[] {null}))));
    }

    @Test
    public void testInPredicateWithCasts()
    {
        assertPredicateTranslates(
                new InPredicate(
                        C_BIGINT.toSymbolReference(),
                        new InListExpression(ImmutableList.of(cast(toExpression(1L, SMALLINT), BIGINT)))),
                withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.singleValue(BIGINT, 1L))));

        assertPredicateTranslates(
                new InPredicate(
                        cast(C_SMALLINT, BIGINT),
                        new InListExpression(ImmutableList.of(toExpression(1L, BIGINT)))),
                withColumnDomains(ImmutableMap.of(C_SMALLINT, Domain.singleValue(SMALLINT, 1L))));

        assertUnsupportedPredicate(new InPredicate(
                cast(C_BIGINT, INTEGER),
                new InListExpression(ImmutableList.of(toExpression(1L, INTEGER)))));
    }

    @Test
    public void testFromInPredicateWithCastsAndNulls()
    {
        assertPredicateIsAlwaysFalse(new InPredicate(
                C_BIGINT.toSymbolReference(),
                new InListExpression(ImmutableList.of(cast(toExpression(null, SMALLINT), BIGINT)))));

        assertUnsupportedPredicate(not(new InPredicate(
                cast(C_SMALLINT, BIGINT),
                new InListExpression(ImmutableList.of(toExpression(null, BIGINT))))));

        assertPredicateTranslates(
                new InPredicate(
                        C_BIGINT.toSymbolReference(),
                        new InListExpression(ImmutableList.of(cast(toExpression(null, SMALLINT), BIGINT), toExpression(1L, BIGINT)))),
                withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.equal(BIGINT, 1L)), false))));

        assertPredicateIsAlwaysFalse(not(new InPredicate(
                C_BIGINT.toSymbolReference(),
                new InListExpression(ImmutableList.of(cast(toExpression(null, SMALLINT), BIGINT), toExpression(1L, SMALLINT))))));
    }

    @Test
    public void testFromBetweenPredicate()
    {
        assertPredicateTranslates(
                between(C_BIGINT, bigintLiteral(1L), bigintLiteral(2L)),
                withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.range(BIGINT, 1L, true, 2L, true)), false))));

        assertPredicateTranslates(
                between(cast(C_INTEGER, DOUBLE), cast(bigintLiteral(1L), DOUBLE), doubleLiteral(2.1)),
                withColumnDomains(ImmutableMap.of(C_INTEGER, Domain.create(ValueSet.ofRanges(Range.range(INTEGER, 1L, true, 2L, true)), false))));

        assertPredicateIsAlwaysFalse(between(C_BIGINT, bigintLiteral(1L), nullLiteral(BIGINT)));

        // Test complements
        assertPredicateTranslates(
                not(between(C_BIGINT, bigintLiteral(1L), bigintLiteral(2L))),
                withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.lessThan(BIGINT, 1L), Range.greaterThan(BIGINT, 2L)), false))));

        assertPredicateTranslates(
                not(between(cast(C_INTEGER, DOUBLE), cast(bigintLiteral(1L), DOUBLE), doubleLiteral(2.1))),
                withColumnDomains(ImmutableMap.of(C_INTEGER, Domain.create(ValueSet.ofRanges(Range.lessThan(INTEGER, 1L), Range.greaterThan(INTEGER, 2L)), false))));

        assertPredicateTranslates(
                not(between(C_BIGINT, bigintLiteral(1L), nullLiteral(BIGINT))),
                withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.create(ValueSet.ofRanges(Range.lessThan(BIGINT, 1L)), false))));
    }

    @Test
    public void testFromIsNullPredicate()
    {
        assertPredicateTranslates(
                isNull(C_BIGINT),
                withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.onlyNull(BIGINT))));

        assertPredicateTranslates(
                isNull(C_HYPER_LOG_LOG),
                withColumnDomains(ImmutableMap.of(C_HYPER_LOG_LOG, Domain.onlyNull(HYPER_LOG_LOG))));

        assertPredicateTranslates(
                not(isNull(C_BIGINT)),
                withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.notNull(BIGINT))));

        assertPredicateTranslates(
                not(isNull(C_HYPER_LOG_LOG)),
                withColumnDomains(ImmutableMap.of(C_HYPER_LOG_LOG, Domain.notNull(HYPER_LOG_LOG))));
    }

    @Test
    public void testFromIsNotNullPredicate()
    {
        assertPredicateTranslates(
                isNotNull(C_BIGINT),
                withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.notNull(BIGINT))));

        assertPredicateTranslates(
                isNotNull(C_HYPER_LOG_LOG),
                withColumnDomains(ImmutableMap.of(C_HYPER_LOG_LOG, Domain.notNull(HYPER_LOG_LOG))));

        assertPredicateTranslates(
                not(isNotNull(C_BIGINT)),
                withColumnDomains(ImmutableMap.of(C_BIGINT, Domain.onlyNull(BIGINT))));

        assertPredicateTranslates(
                not(isNotNull(C_HYPER_LOG_LOG)),
                withColumnDomains(ImmutableMap.of(C_HYPER_LOG_LOG, Domain.onlyNull(HYPER_LOG_LOG))));
    }

    @Test
    public void testFromBooleanLiteralPredicate()
    {
        assertPredicateIsAlwaysTrue(TRUE_LITERAL);
        assertPredicateIsAlwaysFalse(not(TRUE_LITERAL));
        assertPredicateIsAlwaysFalse(FALSE_LITERAL);
        assertPredicateIsAlwaysTrue(not(FALSE_LITERAL));
    }

    @Test
    public void testFromNullLiteralPredicate()
    {
        assertPredicateIsAlwaysFalse(nullLiteral());
        assertPredicateIsAlwaysFalse(not(nullLiteral()));
    }

    @Test
    public void testExpressionConstantFolding()
    {
        FunctionCall fromHex = new FunctionCallBuilder(metadata)
                .setName(QualifiedName.of("from_hex"))
                .addArgument(VARCHAR, stringLiteral("123456"))
                .build();
        Expression originalExpression = comparison(GREATER_THAN, C_VARBINARY.toSymbolReference(), fromHex);
        ExtractionResult result = fromPredicate(originalExpression);
        assertEquals(result.getRemainingExpression(), TRUE_LITERAL);
        Slice value = Slices.wrappedBuffer(BaseEncoding.base16().decode("123456"));
        assertEquals(result.getTupleDomain(), withColumnDomains(ImmutableMap.of(C_VARBINARY, Domain.create(ValueSet.ofRanges(Range.greaterThan(VARBINARY, value)), false))));

        Expression expression = toPredicate(result.getTupleDomain());
        assertEquals(expression, comparison(GREATER_THAN, C_VARBINARY.toSymbolReference(), varbinaryLiteral(value)));
    }

    @Test
    public void testConjunctExpression()
    {
        Expression expression = and(
                comparison(GREATER_THAN, C_DOUBLE.toSymbolReference(), doubleLiteral(0)),
                comparison(GREATER_THAN, C_BIGINT.toSymbolReference(), bigintLiteral(0)));
        assertPredicateTranslates(
                expression,
                withColumnDomains(ImmutableMap.of(
                        C_DOUBLE, Domain.create(ValueSet.ofRanges(Range.greaterThan(DOUBLE, .0)), false),
                        C_BIGINT, Domain.create(ValueSet.ofRanges(Range.greaterThan(BIGINT, 0L)), false))));

        assertEquals(
                toPredicate(fromPredicate(expression).getTupleDomain()),
                and(
                        comparison(GREATER_THAN, C_DOUBLE.toSymbolReference(), doubleLiteral(0)),
                        comparison(GREATER_THAN, C_BIGINT.toSymbolReference(), bigintLiteral(0))));
    }

    @Test
    public void testMultipleCoercionsOnSymbolSide()
    {
        assertPredicateTranslates(
                comparison(GREATER_THAN, cast(cast(C_SMALLINT, REAL), DOUBLE), doubleLiteral(3.7)),
                withColumnDomains(ImmutableMap.of(C_SMALLINT, Domain.create(ValueSet.ofRanges(Range.greaterThan(SMALLINT, 3L)), false))));
    }

    @Test
    public void testNumericTypeTranslation()
    {
        testNumericTypeTranslationChain(
                new NumericValues<>(C_DECIMAL_26_5, longDecimal("-999999999999999999999.99999"), longDecimal("-22.00000"), longDecimal("-44.55569"), longDecimal("23.00000"), longDecimal("44.55567"), longDecimal("999999999999999999999.99999")),
                new NumericValues<>(C_DECIMAL_23_4, longDecimal("-9999999999999999999.9999"), longDecimal("-22.0000"), longDecimal("-44.5557"), longDecimal("23.0000"), longDecimal("44.5556"), longDecimal("9999999999999999999.9999")),
                new NumericValues<>(C_BIGINT, Long.MIN_VALUE, -22L, -45L, 23L, 44L, Long.MAX_VALUE),
                new NumericValues<>(C_DECIMAL_21_3, longDecimal("-999999999999999999.999"), longDecimal("-22.000"), longDecimal("-44.556"), longDecimal("23.000"), longDecimal("44.555"), longDecimal("999999999999999999.999")),
                new NumericValues<>(C_DECIMAL_12_2, shortDecimal("-9999999999.99"), shortDecimal("-22.00"), shortDecimal("-44.56"), shortDecimal("23.00"), shortDecimal("44.55"), shortDecimal("9999999999.99")),
                new NumericValues<>(C_INTEGER, (long) Integer.MIN_VALUE, -22L, -45L, 23L, 44L, (long) Integer.MAX_VALUE),
                new NumericValues<>(C_DECIMAL_6_1, shortDecimal("-99999.9"), shortDecimal("-22.0"), shortDecimal("-44.6"), shortDecimal("23.0"), shortDecimal("44.5"), shortDecimal("99999.9")),
                new NumericValues<>(C_SMALLINT, (long) Short.MIN_VALUE, -22L, -45L, 23L, 44L, (long) Short.MAX_VALUE),
                new NumericValues<>(C_DECIMAL_3_0, shortDecimal("-999"), shortDecimal("-22"), shortDecimal("-45"), shortDecimal("23"), shortDecimal("44"), shortDecimal("999")),
                new NumericValues<>(C_TINYINT, (long) Byte.MIN_VALUE, -22L, -45L, 23L, 44L, (long) Byte.MAX_VALUE),
                new NumericValues<>(C_DECIMAL_2_0, shortDecimal("-99"), shortDecimal("-22"), shortDecimal("-45"), shortDecimal("23"), shortDecimal("44"), shortDecimal("99")));

        testNumericTypeTranslationChain(
                new NumericValues<>(C_DOUBLE, -1.0 * Double.MAX_VALUE, -22.0, -44.5556836, 23.0, 44.5556789, Double.MAX_VALUE),
                new NumericValues<>(C_REAL, realValue(-1.0f * Float.MAX_VALUE), realValue(-22.0f), realValue(-44.555687f), realValue(23.0f), realValue(44.555676f), realValue(Float.MAX_VALUE)));
    }

    private void testNumericTypeTranslationChain(NumericValues<?>... translationChain)
    {
        for (int literalIndex = 0; literalIndex < translationChain.length; literalIndex++) {
            for (int columnIndex = literalIndex + 1; columnIndex < translationChain.length; columnIndex++) {
                NumericValues<?> literal = translationChain[literalIndex];
                NumericValues<?> column = translationChain[columnIndex];
                testNumericTypeTranslation(column, literal);
            }
        }
    }

    private void testNumericTypeTranslation(NumericValues<?> columnValues, NumericValues<?> literalValues)
    {
        Type columnType = columnValues.getType();
        Type literalType = literalValues.getType();
        Type superType = new TypeCoercion(metadata::getType).getCommonSuperType(columnType, literalType).orElseThrow(() -> new IllegalArgumentException("incompatible types in test (" + columnType + ", " + literalType + ")"));

        Expression max = toExpression(literalValues.getMax(), literalType);
        Expression min = toExpression(literalValues.getMin(), literalType);
        Expression integerPositive = toExpression(literalValues.getIntegerPositive(), literalType);
        Expression integerNegative = toExpression(literalValues.getIntegerNegative(), literalType);
        Expression fractionalPositive = toExpression(literalValues.getFractionalPositive(), literalType);
        Expression fractionalNegative = toExpression(literalValues.getFractionalNegative(), literalType);

        if (!literalType.equals(superType)) {
            max = cast(max, superType);
            min = cast(min, superType);
            integerPositive = cast(integerPositive, superType);
            integerNegative = cast(integerNegative, superType);
            fractionalPositive = cast(fractionalPositive, superType);
            fractionalNegative = cast(fractionalNegative, superType);
        }

        Symbol columnSymbol = columnValues.getColumn();
        Expression columnExpression = columnSymbol.toSymbolReference();

        if (!columnType.equals(superType)) {
            columnExpression = cast(columnExpression, superType);
        }

        // greater than or equal
        testSimpleComparison(greaterThanOrEqual(columnExpression, integerPositive), columnSymbol, Range.greaterThanOrEqual(columnType, columnValues.getIntegerPositive()));
        testSimpleComparison(greaterThanOrEqual(columnExpression, integerNegative), columnSymbol, Range.greaterThanOrEqual(columnType, columnValues.getIntegerNegative()));
        testSimpleComparison(greaterThanOrEqual(columnExpression, max), columnSymbol, Range.greaterThan(columnType, columnValues.getMax()));
        testSimpleComparison(greaterThanOrEqual(columnExpression, min), columnSymbol, Range.greaterThanOrEqual(columnType, columnValues.getMin()));
        if (literalValues.isFractional()) {
            testSimpleComparison(greaterThanOrEqual(columnExpression, fractionalPositive), columnSymbol, Range.greaterThan(columnType, columnValues.getFractionalPositive()));
            testSimpleComparison(greaterThanOrEqual(columnExpression, fractionalNegative), columnSymbol, Range.greaterThan(columnType, columnValues.getFractionalNegative()));
        }

        // greater than or equal negated
        if (literalValues.isTypeWithNaN()) {
            assertNoFullPushdown(not(greaterThanOrEqual(columnExpression, integerPositive)));
            assertNoFullPushdown(not(greaterThanOrEqual(columnExpression, integerNegative)));
            assertNoFullPushdown(not(greaterThanOrEqual(columnExpression, max)));
            assertNoFullPushdown(not(greaterThanOrEqual(columnExpression, min)));
            assertNoFullPushdown(not(greaterThanOrEqual(columnExpression, fractionalPositive)));
            assertNoFullPushdown(not(greaterThanOrEqual(columnExpression, fractionalNegative)));
        }
        else {
            testSimpleComparison(not(greaterThanOrEqual(columnExpression, integerPositive)), columnSymbol, Range.lessThan(columnType, columnValues.getIntegerPositive()));
            testSimpleComparison(not(greaterThanOrEqual(columnExpression, integerNegative)), columnSymbol, Range.lessThan(columnType, columnValues.getIntegerNegative()));
            testSimpleComparison(not(greaterThanOrEqual(columnExpression, max)), columnSymbol, Range.lessThanOrEqual(columnType, columnValues.getMax()));
            testSimpleComparison(not(greaterThanOrEqual(columnExpression, min)), columnSymbol, Range.lessThan(columnType, columnValues.getMin()));
            if (literalValues.isFractional()) {
                testSimpleComparison(not(greaterThanOrEqual(columnExpression, fractionalPositive)), columnSymbol, Range.lessThanOrEqual(columnType, columnValues.getFractionalPositive()));
                testSimpleComparison(not(greaterThanOrEqual(columnExpression, fractionalNegative)), columnSymbol, Range.lessThanOrEqual(columnType, columnValues.getFractionalNegative()));
            }
        }

        // greater than
        testSimpleComparison(greaterThan(columnExpression, integerPositive), columnSymbol, Range.greaterThan(columnType, columnValues.getIntegerPositive()));
        testSimpleComparison(greaterThan(columnExpression, integerNegative), columnSymbol, Range.greaterThan(columnType, columnValues.getIntegerNegative()));
        testSimpleComparison(greaterThan(columnExpression, max), columnSymbol, Range.greaterThan(columnType, columnValues.getMax()));
        testSimpleComparison(greaterThan(columnExpression, min), columnSymbol, Range.greaterThanOrEqual(columnType, columnValues.getMin()));
        if (literalValues.isFractional()) {
            testSimpleComparison(greaterThan(columnExpression, fractionalPositive), columnSymbol, Range.greaterThan(columnType, columnValues.getFractionalPositive()));
            testSimpleComparison(greaterThan(columnExpression, fractionalNegative), columnSymbol, Range.greaterThan(columnType, columnValues.getFractionalNegative()));
        }

        // greater than negated
        if (literalValues.isTypeWithNaN()) {
            assertNoFullPushdown(not(greaterThan(columnExpression, integerPositive)));
            assertNoFullPushdown(not(greaterThan(columnExpression, integerNegative)));
            assertNoFullPushdown(not(greaterThan(columnExpression, max)));
            assertNoFullPushdown(not(greaterThan(columnExpression, min)));
            assertNoFullPushdown(not(greaterThan(columnExpression, fractionalPositive)));
            assertNoFullPushdown(not(greaterThan(columnExpression, fractionalNegative)));
        }
        else {
            testSimpleComparison(not(greaterThan(columnExpression, integerPositive)), columnSymbol, Range.lessThanOrEqual(columnType, columnValues.getIntegerPositive()));
            testSimpleComparison(not(greaterThan(columnExpression, integerNegative)), columnSymbol, Range.lessThanOrEqual(columnType, columnValues.getIntegerNegative()));
            testSimpleComparison(not(greaterThan(columnExpression, max)), columnSymbol, Range.lessThanOrEqual(columnType, columnValues.getMax()));
            testSimpleComparison(not(greaterThan(columnExpression, min)), columnSymbol, Range.lessThan(columnType, columnValues.getMin()));
            if (literalValues.isFractional()) {
                testSimpleComparison(not(greaterThan(columnExpression, fractionalPositive)), columnSymbol, Range.lessThanOrEqual(columnType, columnValues.getFractionalPositive()));
                testSimpleComparison(not(greaterThan(columnExpression, fractionalNegative)), columnSymbol, Range.lessThanOrEqual(columnType, columnValues.getFractionalNegative()));
            }
        }

        // less than or equal
        testSimpleComparison(lessThanOrEqual(columnExpression, integerPositive), columnSymbol, Range.lessThanOrEqual(columnType, columnValues.getIntegerPositive()));
        testSimpleComparison(lessThanOrEqual(columnExpression, integerNegative), columnSymbol, Range.lessThanOrEqual(columnType, columnValues.getIntegerNegative()));
        testSimpleComparison(lessThanOrEqual(columnExpression, max), columnSymbol, Range.lessThanOrEqual(columnType, columnValues.getMax()));
        testSimpleComparison(lessThanOrEqual(columnExpression, min), columnSymbol, Range.lessThan(columnType, columnValues.getMin()));
        if (literalValues.isFractional()) {
            testSimpleComparison(lessThanOrEqual(columnExpression, fractionalPositive), columnSymbol, Range.lessThanOrEqual(columnType, columnValues.getFractionalPositive()));
            testSimpleComparison(lessThanOrEqual(columnExpression, fractionalNegative), columnSymbol, Range.lessThanOrEqual(columnType, columnValues.getFractionalNegative()));
        }

        // less than or equal negated
        if (literalValues.isTypeWithNaN()) {
            assertNoFullPushdown(not(lessThanOrEqual(columnExpression, integerPositive)));
            assertNoFullPushdown(not(lessThanOrEqual(columnExpression, integerNegative)));
            assertNoFullPushdown(not(lessThanOrEqual(columnExpression, max)));
            assertNoFullPushdown(not(lessThanOrEqual(columnExpression, min)));
            assertNoFullPushdown(not(lessThanOrEqual(columnExpression, fractionalPositive)));
            assertNoFullPushdown(not(lessThanOrEqual(columnExpression, fractionalNegative)));
        }
        else {
            testSimpleComparison(not(lessThanOrEqual(columnExpression, integerPositive)), columnSymbol, Range.greaterThan(columnType, columnValues.getIntegerPositive()));
            testSimpleComparison(not(lessThanOrEqual(columnExpression, integerNegative)), columnSymbol, Range.greaterThan(columnType, columnValues.getIntegerNegative()));
            testSimpleComparison(not(lessThanOrEqual(columnExpression, max)), columnSymbol, Range.greaterThan(columnType, columnValues.getMax()));
            testSimpleComparison(not(lessThanOrEqual(columnExpression, min)), columnSymbol, Range.greaterThanOrEqual(columnType, columnValues.getMin()));
            if (literalValues.isFractional()) {
                testSimpleComparison(not(lessThanOrEqual(columnExpression, fractionalPositive)), columnSymbol, Range.greaterThan(columnType, columnValues.getFractionalPositive()));
                testSimpleComparison(not(lessThanOrEqual(columnExpression, fractionalNegative)), columnSymbol, Range.greaterThan(columnType, columnValues.getFractionalNegative()));
            }
        }

        // less than
        testSimpleComparison(lessThan(columnExpression, integerPositive), columnSymbol, Range.lessThan(columnType, columnValues.getIntegerPositive()));
        testSimpleComparison(lessThan(columnExpression, integerNegative), columnSymbol, Range.lessThan(columnType, columnValues.getIntegerNegative()));
        testSimpleComparison(lessThan(columnExpression, max), columnSymbol, Range.lessThanOrEqual(columnType, columnValues.getMax()));
        testSimpleComparison(lessThan(columnExpression, min), columnSymbol, Range.lessThan(columnType, columnValues.getMin()));
        if (literalValues.isFractional()) {
            testSimpleComparison(lessThan(columnExpression, fractionalPositive), columnSymbol, Range.lessThanOrEqual(columnType, columnValues.getFractionalPositive()));
            testSimpleComparison(lessThan(columnExpression, fractionalNegative), columnSymbol, Range.lessThanOrEqual(columnType, columnValues.getFractionalNegative()));
        }

        // less than negated
        if (literalValues.isTypeWithNaN()) {
            assertNoFullPushdown(not(lessThan(columnExpression, integerPositive)));
            assertNoFullPushdown(not(lessThan(columnExpression, integerNegative)));
            assertNoFullPushdown(not(lessThan(columnExpression, max)));
            assertNoFullPushdown(not(lessThan(columnExpression, min)));
            assertNoFullPushdown(not(lessThan(columnExpression, fractionalPositive)));
            assertNoFullPushdown(not(lessThan(columnExpression, fractionalNegative)));
        }
        else {
            testSimpleComparison(not(lessThan(columnExpression, integerPositive)), columnSymbol, Range.greaterThanOrEqual(columnType, columnValues.getIntegerPositive()));
            testSimpleComparison(not(lessThan(columnExpression, integerNegative)), columnSymbol, Range.greaterThanOrEqual(columnType, columnValues.getIntegerNegative()));
            testSimpleComparison(not(lessThan(columnExpression, max)), columnSymbol, Range.greaterThan(columnType, columnValues.getMax()));
            testSimpleComparison(not(lessThan(columnExpression, min)), columnSymbol, Range.greaterThanOrEqual(columnType, columnValues.getMin()));
            if (literalValues.isFractional()) {
                testSimpleComparison(not(lessThan(columnExpression, fractionalPositive)), columnSymbol, Range.greaterThan(columnType, columnValues.getFractionalPositive()));
                testSimpleComparison(not(lessThan(columnExpression, fractionalNegative)), columnSymbol, Range.greaterThan(columnType, columnValues.getFractionalNegative()));
            }
        }

        // equal
        testSimpleComparison(equal(columnExpression, integerPositive), columnSymbol, Range.equal(columnType, columnValues.getIntegerPositive()));
        testSimpleComparison(equal(columnExpression, integerNegative), columnSymbol, Range.equal(columnType, columnValues.getIntegerNegative()));
        testSimpleComparison(equal(columnExpression, max), columnSymbol, Domain.none(columnType));
        testSimpleComparison(equal(columnExpression, min), columnSymbol, Domain.none(columnType));
        if (literalValues.isFractional()) {
            testSimpleComparison(equal(columnExpression, fractionalPositive), columnSymbol, Domain.none(columnType));
            testSimpleComparison(equal(columnExpression, fractionalNegative), columnSymbol, Domain.none(columnType));
        }

        // equal negated
        if (literalValues.isTypeWithNaN()) {
            assertNoFullPushdown(not(equal(columnExpression, integerPositive)));
            assertNoFullPushdown(not(equal(columnExpression, integerNegative)));
            assertNoFullPushdown(not(equal(columnExpression, max)));
            assertNoFullPushdown(not(equal(columnExpression, min)));
            assertNoFullPushdown(not(equal(columnExpression, fractionalPositive)));
            assertNoFullPushdown(not(equal(columnExpression, fractionalNegative)));
        }
        else {
            testSimpleComparison(not(equal(columnExpression, integerPositive)), columnSymbol, Domain.create(ValueSet.ofRanges(Range.lessThan(columnType, columnValues.getIntegerPositive()), Range.greaterThan(columnType, columnValues.getIntegerPositive())), false));
            testSimpleComparison(not(equal(columnExpression, integerNegative)), columnSymbol, Domain.create(ValueSet.ofRanges(Range.lessThan(columnType, columnValues.getIntegerNegative()), Range.greaterThan(columnType, columnValues.getIntegerNegative())), false));
            testSimpleComparison(not(equal(columnExpression, max)), columnSymbol, Domain.notNull(columnType));
            testSimpleComparison(not(equal(columnExpression, min)), columnSymbol, Domain.notNull(columnType));
            if (literalValues.isFractional()) {
                testSimpleComparison(not(equal(columnExpression, fractionalPositive)), columnSymbol, Domain.notNull(columnType));
                testSimpleComparison(not(equal(columnExpression, fractionalNegative)), columnSymbol, Domain.notNull(columnType));
            }
        }

        // not equal
        if (literalValues.isTypeWithNaN()) {
            assertNoFullPushdown(notEqual(columnExpression, integerPositive));
            assertNoFullPushdown(notEqual(columnExpression, integerNegative));
            assertNoFullPushdown(notEqual(columnExpression, max));
            assertNoFullPushdown(notEqual(columnExpression, min));
            assertNoFullPushdown(notEqual(columnExpression, fractionalPositive));
            assertNoFullPushdown(notEqual(columnExpression, integerNegative));
        }
        else {
            testSimpleComparison(notEqual(columnExpression, integerPositive), columnSymbol, Domain.create(ValueSet.ofRanges(Range.lessThan(columnType, columnValues.getIntegerPositive()), Range.greaterThan(columnType, columnValues.getIntegerPositive())), false));
            testSimpleComparison(notEqual(columnExpression, integerNegative), columnSymbol, Domain.create(ValueSet.ofRanges(Range.lessThan(columnType, columnValues.getIntegerNegative()), Range.greaterThan(columnType, columnValues.getIntegerNegative())), false));
            testSimpleComparison(notEqual(columnExpression, max), columnSymbol, Domain.notNull(columnType));
            testSimpleComparison(notEqual(columnExpression, min), columnSymbol, Domain.notNull(columnType));
            if (literalValues.isFractional()) {
                testSimpleComparison(notEqual(columnExpression, fractionalPositive), columnSymbol, Domain.notNull(columnType));
                testSimpleComparison(notEqual(columnExpression, fractionalNegative), columnSymbol, Domain.notNull(columnType));
            }
        }

        // not equal negated
        if (literalValues.isTypeWithNaN()) {
            testSimpleComparison(not(notEqual(columnExpression, integerPositive)), columnSymbol, Range.equal(columnType, columnValues.getIntegerPositive()));
            testSimpleComparison(not(notEqual(columnExpression, integerNegative)), columnSymbol, Range.equal(columnType, columnValues.getIntegerNegative()));
            assertNoFullPushdown(not(notEqual(columnExpression, max)));
            assertNoFullPushdown(not(notEqual(columnExpression, min)));
            assertNoFullPushdown(not(notEqual(columnExpression, fractionalPositive)));
            assertNoFullPushdown(not(notEqual(columnExpression, fractionalNegative)));
        }
        else {
            testSimpleComparison(not(notEqual(columnExpression, integerPositive)), columnSymbol, Range.equal(columnType, columnValues.getIntegerPositive()));
            testSimpleComparison(not(notEqual(columnExpression, integerNegative)), columnSymbol, Range.equal(columnType, columnValues.getIntegerNegative()));
            testSimpleComparison(not(notEqual(columnExpression, max)), columnSymbol, Domain.none(columnType));
            testSimpleComparison(not(notEqual(columnExpression, min)), columnSymbol, Domain.none(columnType));
            if (literalValues.isFractional()) {
                testSimpleComparison(not(notEqual(columnExpression, fractionalPositive)), columnSymbol, Domain.none(columnType));
                testSimpleComparison(not(notEqual(columnExpression, fractionalNegative)), columnSymbol, Domain.none(columnType));
            }
        }

        // is distinct from
        if (literalValues.isTypeWithNaN()) {
            assertNoFullPushdown(isDistinctFrom(columnExpression, integerPositive));
            assertNoFullPushdown(isDistinctFrom(columnExpression, integerNegative));
            testSimpleComparison(isDistinctFrom(columnExpression, max), columnSymbol, Domain.all(columnType));
            testSimpleComparison(isDistinctFrom(columnExpression, min), columnSymbol, Domain.all(columnType));
            testSimpleComparison(isDistinctFrom(columnExpression, fractionalPositive), columnSymbol, Domain.all(columnType));
            testSimpleComparison(isDistinctFrom(columnExpression, fractionalNegative), columnSymbol, Domain.all(columnType));
        }
        else {
            testSimpleComparison(isDistinctFrom(columnExpression, integerPositive), columnSymbol, Domain.create(ValueSet.ofRanges(Range.lessThan(columnType, columnValues.getIntegerPositive()), Range.greaterThan(columnType, columnValues.getIntegerPositive())), true));
            testSimpleComparison(isDistinctFrom(columnExpression, integerNegative), columnSymbol, Domain.create(ValueSet.ofRanges(Range.lessThan(columnType, columnValues.getIntegerNegative()), Range.greaterThan(columnType, columnValues.getIntegerNegative())), true));
            testSimpleComparison(isDistinctFrom(columnExpression, max), columnSymbol, Domain.all(columnType));
            testSimpleComparison(isDistinctFrom(columnExpression, min), columnSymbol, Domain.all(columnType));
            if (literalValues.isFractional()) {
                testSimpleComparison(isDistinctFrom(columnExpression, fractionalPositive), columnSymbol, Domain.all(columnType));
                testSimpleComparison(isDistinctFrom(columnExpression, fractionalNegative), columnSymbol, Domain.all(columnType));
            }
        }

        // is distinct from negated
        testSimpleComparison(not(isDistinctFrom(columnExpression, integerPositive)), columnSymbol, Range.equal(columnType, columnValues.getIntegerPositive()));
        testSimpleComparison(not(isDistinctFrom(columnExpression, integerNegative)), columnSymbol, Range.equal(columnType, columnValues.getIntegerNegative()));
        testSimpleComparison(not(isDistinctFrom(columnExpression, max)), columnSymbol, Domain.none(columnType));
        testSimpleComparison(not(isDistinctFrom(columnExpression, min)), columnSymbol, Domain.none(columnType));
        if (literalValues.isFractional()) {
            testSimpleComparison(not(isDistinctFrom(columnExpression, fractionalPositive)), columnSymbol, Domain.none(columnType));
            testSimpleComparison(not(isDistinctFrom(columnExpression, fractionalNegative)), columnSymbol, Domain.none(columnType));
        }
    }

    @Test
    public void testCharComparedToVarcharExpression()
    {
        Type charType = createCharType(10);
        // varchar literal is coerced to column (char) type
        testSimpleComparison(equal(C_CHAR, cast(stringLiteral("abc"), charType)), C_CHAR, Range.equal(charType, Slices.utf8Slice("abc")));

        // both sides got coerced to char(11)
        charType = createCharType(11);
        assertUnsupportedPredicate(equal(cast(C_CHAR, charType), cast(stringLiteral("abc12345678"), charType)));
    }

    private void assertPredicateIsAlwaysTrue(Expression expression)
    {
        assertPredicateTranslates(expression, TupleDomain.all());
    }

    private void assertPredicateIsAlwaysFalse(Expression expression)
    {
        ExtractionResult result = fromPredicate(expression);
        assertEquals(result.getRemainingExpression(), TRUE_LITERAL);
        assertTrue(result.getTupleDomain().isNone());
    }

    private void assertUnsupportedPredicate(Expression expression)
    {
        ExtractionResult result = fromPredicate(expression);
        assertEquals(result.getRemainingExpression(), expression);
        assertEquals(result.getTupleDomain(), TupleDomain.all());
    }

    private void assertPredicateTranslates(Expression expression, TupleDomain<Symbol> tupleDomain)
    {
        ExtractionResult result = fromPredicate(expression);
        assertEquals(result.getRemainingExpression(), TRUE_LITERAL);
        assertEquals(result.getTupleDomain(), tupleDomain);
    }

    private void assertNoFullPushdown(Expression expression)
    {
        ExtractionResult result = fromPredicate(expression);
        assertNotEquals(result.getRemainingExpression(), TRUE_LITERAL);
    }

    private ExtractionResult fromPredicate(Expression originalPredicate)
    {
        return transaction(new TestingTransactionManager(), new AllowAllAccessControl())
                .singleStatement()
                .execute(TEST_SESSION, transactionSession -> {
                    return DomainTranslator.fromPredicate(metadata, transactionSession, originalPredicate, TYPES);
                });
    }

    private Expression toPredicate(TupleDomain<Symbol> tupleDomain)
    {
        return domainTranslator.toPredicate(tupleDomain);
    }

    private static Expression unprocessableExpression1(Symbol symbol)
    {
        return comparison(GREATER_THAN, symbol.toSymbolReference(), symbol.toSymbolReference());
    }

    private static Expression unprocessableExpression2(Symbol symbol)
    {
        return comparison(LESS_THAN, symbol.toSymbolReference(), symbol.toSymbolReference());
    }

    private Expression randPredicate(Symbol symbol, Type type)
    {
        FunctionCall rand = new FunctionCallBuilder(metadata)
                .setName(QualifiedName.of("rand"))
                .build();
        return comparison(GREATER_THAN, symbol.toSymbolReference(), cast(rand, type));
    }

    private static ComparisonExpression equal(Symbol symbol, Expression expression)
    {
        return equal(symbol.toSymbolReference(), expression);
    }

    private static ComparisonExpression notEqual(Symbol symbol, Expression expression)
    {
        return notEqual(symbol.toSymbolReference(), expression);
    }

    private static ComparisonExpression greaterThan(Symbol symbol, Expression expression)
    {
        return greaterThan(symbol.toSymbolReference(), expression);
    }

    private static ComparisonExpression greaterThanOrEqual(Symbol symbol, Expression expression)
    {
        return greaterThanOrEqual(symbol.toSymbolReference(), expression);
    }

    private static ComparisonExpression lessThan(Symbol symbol, Expression expression)
    {
        return lessThan(symbol.toSymbolReference(), expression);
    }

    private static ComparisonExpression lessThanOrEqual(Symbol symbol, Expression expression)
    {
        return lessThanOrEqual(symbol.toSymbolReference(), expression);
    }

    private static ComparisonExpression isDistinctFrom(Symbol symbol, Expression expression)
    {
        return isDistinctFrom(symbol.toSymbolReference(), expression);
    }

    private static Expression isNotNull(Symbol symbol)
    {
        return isNotNull(symbol.toSymbolReference());
    }

    private static IsNullPredicate isNull(Symbol symbol)
    {
        return new IsNullPredicate(symbol.toSymbolReference());
    }

    private InPredicate in(Symbol symbol, List<?> values)
    {
        return in(symbol.toSymbolReference(), TYPES.get(symbol), values);
    }

    private static BetweenPredicate between(Symbol symbol, Expression min, Expression max)
    {
        return new BetweenPredicate(symbol.toSymbolReference(), min, max);
    }

    private static Expression isNotNull(Expression expression)
    {
        return new NotExpression(new IsNullPredicate(expression));
    }

    private static IsNullPredicate isNull(Expression expression)
    {
        return new IsNullPredicate(expression);
    }

    private InPredicate in(Expression expression, Type expressisonType, List<?> values)
    {
        List<Type> types = nCopies(values.size(), expressisonType);
        List<Expression> expressions = literalEncoder.toExpressions(values, types);
        return new InPredicate(expression, new InListExpression(expressions));
    }

    private static BetweenPredicate between(Expression expression, Expression min, Expression max)
    {
        return new BetweenPredicate(expression, min, max);
    }

    private static ComparisonExpression equal(Expression left, Expression right)
    {
        return comparison(EQUAL, left, right);
    }

    private static ComparisonExpression notEqual(Expression left, Expression right)
    {
        return comparison(NOT_EQUAL, left, right);
    }

    private static ComparisonExpression greaterThan(Expression left, Expression right)
    {
        return comparison(GREATER_THAN, left, right);
    }

    private static ComparisonExpression greaterThanOrEqual(Expression left, Expression right)
    {
        return comparison(GREATER_THAN_OR_EQUAL, left, right);
    }

    private static ComparisonExpression lessThan(Expression left, Expression expression)
    {
        return comparison(LESS_THAN, left, expression);
    }

    private static ComparisonExpression lessThanOrEqual(Expression left, Expression right)
    {
        return comparison(LESS_THAN_OR_EQUAL, left, right);
    }

    private static ComparisonExpression isDistinctFrom(Expression left, Expression right)
    {
        return comparison(IS_DISTINCT_FROM, left, right);
    }

    private static NotExpression not(Expression expression)
    {
        return new NotExpression(expression);
    }

    private static ComparisonExpression comparison(ComparisonExpression.Operator operator, Expression expression1, Expression expression2)
    {
        return new ComparisonExpression(operator, expression1, expression2);
    }

    private static Literal bigintLiteral(long value)
    {
        if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
            return new GenericLiteral("BIGINT", Long.toString(value));
        }
        return new LongLiteral(Long.toString(value));
    }

    private static DoubleLiteral doubleLiteral(double value)
    {
        return new DoubleLiteral(Double.toString(value));
    }

    private static Expression realLiteral(String value)
    {
        return new GenericLiteral("REAL", value);
    }

    private static StringLiteral stringLiteral(String value)
    {
        return new StringLiteral(value);
    }

    private static Expression stringLiteral(String value, Type type)
    {
        return cast(stringLiteral(value), type);
    }

    private static NullLiteral nullLiteral()
    {
        return new NullLiteral();
    }

    private static Expression nullLiteral(Type type)
    {
        return cast(new NullLiteral(), type);
    }

    private static Expression cast(Symbol symbol, Type type)
    {
        return cast(symbol.toSymbolReference(), type);
    }

    private static Expression cast(Expression expression, Type type)
    {
        return new Cast(expression, toSqlType(type));
    }

    private Expression colorLiteral(long value)
    {
        return literalEncoder.toExpression(value, COLOR);
    }

    private Expression varbinaryLiteral(Slice value)
    {
        return toExpression(value, VARBINARY);
    }

    private static Long shortDecimal(String value)
    {
        return new BigDecimal(value).unscaledValue().longValueExact();
    }

    private static Slice longDecimal(String value)
    {
        return encodeScaledValue(new BigDecimal(value));
    }

    private static Long realValue(float value)
    {
        return (long) Float.floatToIntBits(value);
    }

    private void testSimpleComparison(Expression expression, Symbol symbol, Range expectedDomainRange)
    {
        testSimpleComparison(expression, symbol, Domain.create(ValueSet.ofRanges(expectedDomainRange), false));
    }

    private void testSimpleComparison(Expression expression, Symbol symbol, Domain domain)
    {
        ExtractionResult result = fromPredicate(expression);
        assertEquals(result.getRemainingExpression(), TRUE_LITERAL);
        TupleDomain<Symbol> actual = result.getTupleDomain();
        TupleDomain<Symbol> expected = withColumnDomains(ImmutableMap.of(symbol, domain));
        if (!actual.equals(expected)) {
            fail(format("for comparison [%s] expected %s but found %s", expression.toString(), expected.toString(SESSION), actual.toString(SESSION)));
        }
    }

    private Expression toExpression(Object object, Type type)
    {
        return literalEncoder.toExpression(object, type);
    }

    private static class NumericValues<T>
    {
        private final Symbol column;
        private final Type type;
        private final T min;
        private final T integerNegative;
        private final T fractionalNegative;
        private final T integerPositive;
        private final T fractionalPositive;
        private final T max;

        private NumericValues(Symbol column, T min, T integerNegative, T fractionalNegative, T integerPositive, T fractionalPositive, T max)
        {
            this.column = requireNonNull(column, "column is null");
            this.type = requireNonNull(TYPES.get(column), "type for column not found: " + column);
            this.min = requireNonNull(min, "min is null");
            this.integerNegative = requireNonNull(integerNegative, "integerNegative is null");
            this.fractionalNegative = requireNonNull(fractionalNegative, "fractionalNegative is null");
            this.integerPositive = requireNonNull(integerPositive, "integerPositive is null");
            this.fractionalPositive = requireNonNull(fractionalPositive, "fractionalPositive is null");
            this.max = requireNonNull(max, "max is null");
        }

        public Symbol getColumn()
        {
            return column;
        }

        public Type getType()
        {
            return type;
        }

        public T getMin()
        {
            return min;
        }

        public T getIntegerNegative()
        {
            return integerNegative;
        }

        public T getFractionalNegative()
        {
            return fractionalNegative;
        }

        public T getIntegerPositive()
        {
            return integerPositive;
        }

        public T getFractionalPositive()
        {
            return fractionalPositive;
        }

        public T getMax()
        {
            return max;
        }

        public boolean isFractional()
        {
            return type == DOUBLE || type == REAL || (type instanceof DecimalType && ((DecimalType) type).getScale() > 0);
        }

        public boolean isTypeWithNaN()
        {
            return type instanceof DoubleType || type instanceof RealType;
        }
    }
}
