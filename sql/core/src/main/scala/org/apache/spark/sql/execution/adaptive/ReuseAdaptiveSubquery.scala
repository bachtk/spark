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

package org.apache.spark.sql.execution.adaptive

import scala.collection.concurrent.TrieMap

import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.catalyst.trees.TreePattern.PLAN_EXPRESSION
import org.apache.spark.sql.execution.{BaseSubqueryExec, ExecSubqueryExpression, ReusedSubqueryExec, SparkPlan}

case class ReuseAdaptiveSubquery(
    reuseMap: TrieMap[SparkPlan, BaseSubqueryExec]) extends Rule[SparkPlan] {

  def apply(plan: SparkPlan): SparkPlan = {
    if (!conf.subqueryReuseEnabled) {
      return plan
    }

    plan.transformAllExpressionsWithPruning(_.containsPattern(PLAN_EXPRESSION)) {
      case sub: ExecSubqueryExpression =>
        // The subquery can be already reused (the same Java object) due to filter pushdown
        // of table cache. If it happens, we just need to wrap the current subquery with
        // `ReusedSubqueryExec` and no need to update the `reuseMap`.
        reuseMap.get(sub.plan.canonicalized).map { subquery =>
          sub.withNewPlan(ReusedSubqueryExec(subquery))
        }.getOrElse {
          reuseMap.putIfAbsent(sub.plan.canonicalized, sub.plan) match {
            case Some(subquery) => sub.withNewPlan(ReusedSubqueryExec(subquery))
            case None => sub
          }
        }
    }
  }
}
