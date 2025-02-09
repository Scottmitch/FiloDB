package filodb.query.exec

import monix.eval.Task
import monix.reactive.Observable

import filodb.core.query._
import filodb.query._

/**
  * Simply concatenate results from child ExecPlan objects
  */
trait DistConcatExec extends NonLeafExecPlan {
  require(children.nonEmpty)

  protected def args: String = ""

  protected def compose(childResponses: Observable[(QueryResponse, Int)],
                        firstSchema: Task[ResultSchema],
                        querySession: QuerySession): Observable[RangeVector] = {
    childResponses.flatMap {
      case (QueryResult(_, _, result, _, _, _), _) => Observable.fromIterable(result)
      case (QueryError(_, _, ex), _)         => throw ex
    }
  }
}

/**
  * Use when child ExecPlan's span single local partition
  */
final case class LocalPartitionDistConcatExec(queryContext: QueryContext,
                                              dispatcher: PlanDispatcher,
                                              children: Seq[ExecPlan]) extends DistConcatExec {
  override def reduceSchemas(rs: ResultSchema, resp: QueryResult): ResultSchema = {
    // Given a pushdown-optimized BinaryJoinExec:
    //
    // LocalPartitionDistConcatExec
    // |________
    // |       |
    // BJ      BJ
    // |____   |____
    // |   |   |   |
    // L   R   L   R
    //
    // It's possible each BJ's reduceSchemas returns a slightly-different ResultSchema
    //   (i.e. the left BJ reduceSchemas might process its left child first,
    //   and the right BJ might process its right first. As of this writing, the result is
    //   order-dependent, and the order is non-deterministic). The default reduceSchemas
    //   implementation is too strict (essentially requires equality), and it does not work
    //   for this use-case.
    IgnoreFixedVectorLenAndColumnNamesSchemaReducer.reduceSchema(rs, resp)
  }
}


/**
  * Wrapper/Nonleaf execplan to split long range PeriodicPlan to multiple smaller execs.
  * It executes child plans sequentially and merges results using StitchRvsMapper
  */
final case class SplitLocalPartitionDistConcatExec(queryContext: QueryContext,
                                     dispatcher: PlanDispatcher,
                                     children: Seq[ExecPlan], outputRvRange: Option[RvRange],
                                    override val parallelChildTasks: Boolean = false) extends DistConcatExec {

  addRangeVectorTransformer(StitchRvsMapper(outputRvRange))

  // overriden since it can reduce schemas with different vector lengths as long as the columns are same
  override def reduceSchemas(rs: ResultSchema, resp: QueryResult): ResultSchema =
    IgnoreFixedVectorLenAndColumnNamesSchemaReducer.reduceSchema(rs, resp)
}

/**
  * Use when child ExecPlan's span multiple partitions
  */
final case class MultiPartitionDistConcatExec(queryContext: QueryContext,
                                              dispatcher: PlanDispatcher,
                                              children: Seq[ExecPlan]) extends DistConcatExec {
  // overriden since it can reduce schemas with different vector lengths as long as the columns are same
  override def reduceSchemas(rs: ResultSchema, resp: QueryResult): ResultSchema =
    IgnoreFixedVectorLenAndColumnNamesSchemaReducer.reduceSchema(rs, resp)
}