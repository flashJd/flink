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
package org.apache.flink.table.planner.plan.rules.logical

import org.apache.flink.table.api.{DataTypes, Schema}
import org.apache.flink.table.catalog.{Column, ResolvedSchema}
import org.apache.flink.table.expressions.utils.ResolvedExpressionMock
import org.apache.flink.table.planner.expressions.utils.Func1
import org.apache.flink.table.planner.plan.optimize.program.{FlinkBatchProgram, FlinkHepRuleSetProgramBuilder, HEP_RULES_EXECUTION_TYPE}
import org.apache.flink.table.planner.utils.{BatchTableTestUtil, TableConfigUtils, TableTestBase, TestPartitionableSourceFactory}
import org.apache.flink.testutils.junit.extensions.parameterized.{ParameterizedTestExtension, Parameters}

import org.apache.calcite.plan.hep.HepMatchOrder
import org.apache.calcite.rel.rules.CoreRules
import org.apache.calcite.tools.RuleSets
import org.junit.jupiter.api.{BeforeEach, TestTemplate}
import org.junit.jupiter.api.extension.ExtendWith

import java.util

import scala.collection.JavaConversions._

/** Test for [[PushPartitionIntoLegacyTableSourceScanRule]]. */
@ExtendWith(Array(classOf[ParameterizedTestExtension]))
class PushPartitionIntoLegacyTableSourceScanRuleTest(
    val sourceFetchPartitions: Boolean,
    val useCatalogFilter: Boolean)
  extends TableTestBase {
  protected val util: BatchTableTestUtil = batchTestUtil()

  @throws(classOf[Exception])
  @BeforeEach
  def setup(): Unit = {
    util.buildBatchProgram(FlinkBatchProgram.DEFAULT_REWRITE)
    val calciteConfig = TableConfigUtils.getCalciteConfig(util.tableEnv.getConfig)
    calciteConfig.getBatchProgram.get.addLast(
      "rules",
      FlinkHepRuleSetProgramBuilder.newBuilder
        .setHepRulesExecutionType(HEP_RULES_EXECUTION_TYPE.RULE_COLLECTION)
        .setHepMatchOrder(HepMatchOrder.BOTTOM_UP)
        .add(
          RuleSets.ofList(
            PushPartitionIntoLegacyTableSourceScanRule.INSTANCE,
            CoreRules.FILTER_PROJECT_TRANSPOSE))
        .build()
    )

    val tableSchema = Schema
      .newBuilder()
      .fromResolvedSchema(ResolvedSchema.of(
        Column.physical("id", DataTypes.INT()),
        Column.physical("name", DataTypes.STRING()),
        Column.physical("part1", DataTypes.STRING()),
        Column.physical("part2", DataTypes.INT())
      ))
      .build()

    val tableSchema2 = Schema
      .newBuilder()
      .fromResolvedSchema(ResolvedSchema.of(
        Column.physical("id", DataTypes.INT()),
        Column.physical("name", DataTypes.STRING()),
        Column.physical("part1", DataTypes.STRING()),
        Column.physical("part2", DataTypes.INT()),
        Column.computed("virtualField", ResolvedExpressionMock.of(DataTypes.INT(), "`part2` + 1"))
      ))
      .build()

    TestPartitionableSourceFactory.createTemporaryTable(
      util.tableEnv,
      "MyTable",
      tableSchema = tableSchema,
      isBounded = true)
    TestPartitionableSourceFactory.createTemporaryTable(
      util.tableEnv,
      "VirtualTable",
      tableSchema = tableSchema2,
      isBounded = true)
  }

  @TestTemplate
  def testNoPartitionFieldPredicate(): Unit = {
    util.verifyRelPlan("SELECT * FROM MyTable WHERE id > 2")
  }

  @TestTemplate
  def testNoPartitionFieldPredicateWithVirtualColumn(): Unit = {
    util.verifyRelPlan("SELECT * FROM VirtualTable WHERE id > 2")
  }

  @TestTemplate
  def testOnlyPartitionFieldPredicate1(): Unit = {
    util.verifyRelPlan("SELECT * FROM MyTable WHERE part1 = 'A'")
  }

  @TestTemplate
  def testOnlyPartitionFieldPredicate1WithVirtualColumn(): Unit = {
    util.verifyRelPlan("SELECT * FROM VirtualTable WHERE part1 = 'A'")
  }

  @TestTemplate
  def testOnlyPartitionFieldPredicate2(): Unit = {
    util.verifyRelPlan("SELECT * FROM MyTable WHERE part2 > 1")
  }

  @TestTemplate
  def testOnlyPartitionFieldPredicate2WithVirtualColumn(): Unit = {
    util.verifyRelPlan("SELECT * FROM VirtualTable WHERE part2 > 1")
  }

  @TestTemplate
  def testOnlyPartitionFieldPredicate3(): Unit = {
    util.verifyRelPlan("SELECT * FROM MyTable WHERE part1 = 'A' AND part2 > 1")
  }

  @TestTemplate
  def testOnlyPartitionFieldPredicate3WithVirtualColumn(): Unit = {
    util.verifyRelPlan("SELECT * FROM VirtualTable WHERE part1 = 'A' AND part2 > 1")
  }

  @TestTemplate
  def testOnlyPartitionFieldPredicate4(): Unit = {
    util.verifyRelPlan("SELECT * FROM MyTable WHERE part1 = 'A' OR part2 > 1")
  }

  @TestTemplate
  def testOnlyPartitionFieldPredicate4WithVirtualColumn(): Unit = {
    util.verifyRelPlan("SELECT * FROM VirtualTable WHERE part1 = 'A' OR part2 > 1")
  }

  @TestTemplate
  def testPartitionFieldPredicateAndOtherPredicate(): Unit = {
    util.verifyRelPlan("SELECT * FROM MyTable WHERE id > 2 AND part1 = 'A'")
  }

  @TestTemplate
  def testPartitionFieldPredicateAndOtherPredicateWithVirtualColumn(): Unit = {
    util.verifyRelPlan("SELECT * FROM VirtualTable WHERE id > 2 AND part1 = 'A'")
  }

  @TestTemplate
  def testPartitionFieldPredicateOrOtherPredicate(): Unit = {
    util.verifyRelPlan("SELECT * FROM MyTable WHERE id > 2 OR part1 = 'A'")
  }

  @TestTemplate
  def testPartitionFieldPredicateOrOtherPredicateWithVirtualColumn(): Unit = {
    util.verifyRelPlan("SELECT * FROM VirtualTable WHERE id > 2 OR part1 = 'A'")
  }

  @TestTemplate
  def testPartialPartitionFieldPredicatePushDown(): Unit = {
    util.verifyRelPlan("SELECT * FROM MyTable WHERE (id > 2 OR part1 = 'A') AND part2 > 1")
  }

  @TestTemplate
  def testPartialPartitionFieldPredicatePushDownWithVirtualColumn(): Unit = {
    util.verifyRelPlan("SELECT * FROM VirtualTable WHERE (id > 2 OR part1 = 'A') AND part2 > 1")
  }

  @TestTemplate
  def testWithUdf(): Unit = {
    util.addFunction("MyUdf", Func1)
    util.verifyRelPlan("SELECT * FROM MyTable WHERE id > 2 AND MyUdf(part2) < 3")
  }

  @TestTemplate
  def testWithUdfAndVirtualColumn(): Unit = {
    util.addFunction("MyUdf", Func1)
    util.verifyRelPlan("SELECT * FROM VirtualTable WHERE id > 2 AND MyUdf(part2) < 3")
  }
}

object PushPartitionIntoLegacyTableSourceScanRuleTest {
  @Parameters(name = "sourceFetchPartitions={0}, useCatalogFilter={1}")
  def parameters(): util.Collection[Array[Any]] = {
    Seq[Array[Any]](
      Array(true, false),
      Array(false, false),
      Array(false, true)
    )
  }
}
