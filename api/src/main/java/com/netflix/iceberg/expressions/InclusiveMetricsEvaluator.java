/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.iceberg.expressions;

import com.google.common.base.Preconditions;
import com.netflix.iceberg.DataFile;
import com.netflix.iceberg.Schema;
import com.netflix.iceberg.expressions.ExpressionVisitors.BoundExpressionVisitor;
import com.netflix.iceberg.types.Conversions;
import com.netflix.iceberg.types.Types;
import com.netflix.iceberg.types.Types.StructType;
import java.nio.ByteBuffer;
import java.util.Map;

import static com.netflix.iceberg.expressions.Expressions.rewriteNot;

/**
 * Evaluates an {@link Expression} on a {@link DataFile} to test whether rows in the file may match.
 * <p>
 * This evaluation is inclusive: it returns true if a file may match and false if it cannot match.
 * <p>
 * Files are passed to {@link #eval(DataFile)}, which returns true if the file may contain matching
 * rows and false if the file cannot contain matching rows. Files may be skipped if and only if the
 * return value of {@code eval} is false.
 */
public class InclusiveMetricsEvaluator {
  private final Schema schema;
  private final StructType struct;
  private final Expression expr;
  private transient ThreadLocal<MetricsEvalVisitor> visitors = null;

  private MetricsEvalVisitor visitor() {
    if (visitors == null) {
      this.visitors = ThreadLocal.withInitial(MetricsEvalVisitor::new);
    }
    return visitors.get();
  }

  public InclusiveMetricsEvaluator(Schema schema, Expression unbound) {
    this.schema = schema;
    this.struct = schema.asStruct();
    this.expr = Binder.bind(struct, rewriteNot(unbound));
  }

  /**
   * Test whether the file may contain records that match the expression.
   *
   * @param file a data file
   * @return false if the file cannot contain rows that match the expression, true otherwise.
   */
  public boolean eval(DataFile file) {
    // TODO: detect the case where a column is missing from the file using file's max field id.
    return visitor().eval(file);
  }

  private static final boolean ROWS_MIGHT_MATCH = true;
  private static final boolean ROWS_CANNOT_MATCH = false;

  private class MetricsEvalVisitor extends BoundExpressionVisitor<Boolean> {
    private Map<Integer, Long> valueCounts = null;
    private Map<Integer, Long> nullCounts = null;
    private Map<Integer, ByteBuffer> lowerBounds = null;
    private Map<Integer, ByteBuffer> upperBounds = null;

    private boolean eval(DataFile file) {
      if (file.recordCount() <= 0) {
        return ROWS_CANNOT_MATCH;
      }

      this.valueCounts = file.valueCounts();
      this.nullCounts = file.nullValueCounts();
      this.lowerBounds = file.lowerBounds();
      this.upperBounds = file.upperBounds();

      return ExpressionVisitors.visit(expr, this);
    }

    @Override
    public Boolean alwaysTrue() {
      return ROWS_MIGHT_MATCH; // all rows match
    }

    @Override
    public Boolean alwaysFalse() {
      return ROWS_CANNOT_MATCH; // all rows fail
    }

    @Override
    public Boolean not(Boolean result) {
      return !result;
    }

    @Override
    public Boolean and(Boolean leftResult, Boolean rightResult) {
      return leftResult && rightResult;
    }

    @Override
    public Boolean or(Boolean leftResult, Boolean rightResult) {
      return leftResult || rightResult;
    }

    @Override
    public <T> Boolean isNull(BoundReference<T> ref) {
      // no need to check whether the field is required because binding evaluates that case
      // if the column has no null values, the expression cannot match
      Integer id = ref.fieldId();
      Preconditions.checkNotNull(struct.field(id),
          "Cannot filter by nested column: %s", schema.findField(id));

      if (nullCounts != null && nullCounts.containsKey(id) && nullCounts.get(id) == 0) {
        return ROWS_CANNOT_MATCH;
      }

      return ROWS_MIGHT_MATCH;
    }

    @Override
    public <T> Boolean notNull(BoundReference<T> ref) {
      // no need to check whether the field is required because binding evaluates that case
      // if the column has no non-null values, the expression cannot match
      Integer id = ref.fieldId();
      Preconditions.checkNotNull(struct.field(id),
          "Cannot filter by nested column: %s", schema.findField(id));

      if (valueCounts != null && valueCounts.containsKey(id) &&
          nullCounts != null && nullCounts.containsKey(id) &&
          valueCounts.get(id) - nullCounts.get(id) == 0) {
        return ROWS_CANNOT_MATCH;
      }

      return ROWS_MIGHT_MATCH;
    }

    @Override
    public <T> Boolean lt(BoundReference<T> ref, Literal<T> lit) {
      Integer id = ref.fieldId();
      Types.NestedField field = struct.field(id);
      Preconditions.checkNotNull(field, "Cannot filter by nested column: %s", schema.findField(id));

      if (lowerBounds != null && lowerBounds.containsKey(id)) {
        T lower = Conversions.fromByteBuffer(field.type(), lowerBounds.get(id));

        int cmp = lit.comparator().compare(lower, lit.value());
        if (cmp >= 0) {
          return ROWS_CANNOT_MATCH;
        }
      }

      return ROWS_MIGHT_MATCH;
    }

    @Override
    public <T> Boolean ltEq(BoundReference<T> ref, Literal<T> lit) {
      Integer id = ref.fieldId();
      Types.NestedField field = struct.field(id);
      Preconditions.checkNotNull(field, "Cannot filter by nested column: %s", schema.findField(id));

      if (lowerBounds != null && lowerBounds.containsKey(id)) {
        T lower = Conversions.fromByteBuffer(field.type(), lowerBounds.get(id));

        int cmp = lit.comparator().compare(lower, lit.value());
        if (cmp > 0) {
          return ROWS_CANNOT_MATCH;
        }
      }

      return ROWS_MIGHT_MATCH;
    }

    @Override
    public <T> Boolean gt(BoundReference<T> ref, Literal<T> lit) {
      Integer id = ref.fieldId();
      Types.NestedField field = struct.field(id);
      Preconditions.checkNotNull(field, "Cannot filter by nested column: %s", schema.findField(id));

      if (upperBounds != null && upperBounds.containsKey(id)) {
        T upper = Conversions.fromByteBuffer(field.type(), upperBounds.get(id));

        int cmp = lit.comparator().compare(upper, lit.value());
        if (cmp <= 0) {
          return ROWS_CANNOT_MATCH;
        }
      }

      return ROWS_MIGHT_MATCH;
    }

    @Override
    public <T> Boolean gtEq(BoundReference<T> ref, Literal<T> lit) {
      Integer id = ref.fieldId();
      Types.NestedField field = struct.field(id);
      Preconditions.checkNotNull(field, "Cannot filter by nested column: %s", schema.findField(id));

      if (upperBounds != null && upperBounds.containsKey(id)) {
        T upper = Conversions.fromByteBuffer(field.type(), upperBounds.get(id));

        int cmp = lit.comparator().compare(upper, lit.value());
        if (cmp < 0) {
          return ROWS_CANNOT_MATCH;
        }
      }

      return ROWS_MIGHT_MATCH;
    }

    @Override
    public <T> Boolean eq(BoundReference<T> ref, Literal<T> lit) {
      Integer id = ref.fieldId();
      Types.NestedField field = struct.field(id);
      Preconditions.checkNotNull(field, "Cannot filter by nested column: %s", schema.findField(id));

      if (lowerBounds != null && lowerBounds.containsKey(id)) {
        T lower = Conversions.fromByteBuffer(struct.field(id).type(), lowerBounds.get(id));

        int cmp = lit.comparator().compare(lower, lit.value());
        if (cmp > 0) {
          return ROWS_CANNOT_MATCH;
        }
      }

      if (upperBounds != null && upperBounds.containsKey(id)) {
        T upper = Conversions.fromByteBuffer(field.type(), upperBounds.get(id));

        int cmp = lit.comparator().compare(upper, lit.value());
        if (cmp < 0) {
          return ROWS_CANNOT_MATCH;
        }
      }

      return ROWS_MIGHT_MATCH;
    }

    @Override
    public <T> Boolean notEq(BoundReference<T> ref, Literal<T> lit) {
      // because the bounds are not necessarily a min or max value, this cannot be answered using
      // them. notEq(col, X) with (X, Y) doesn't guarantee that X is a value in col.
      return ROWS_MIGHT_MATCH;
    }

    @Override
    public <T> Boolean in(BoundReference<T> ref, Literal<T> lit) {
      return ROWS_MIGHT_MATCH;
    }

    @Override
    public <T> Boolean notIn(BoundReference<T> ref, Literal<T> lit) {
      return ROWS_MIGHT_MATCH;
    }
  }
}
