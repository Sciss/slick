package org.scalaquery.ql

import scala.reflect.Manifest
import org.scalaquery.SQueryException
import org.scalaquery.util._

/**
 * A query monad which contains the AST for a query's projection and the accumulated
 * restrictions and other modifiers.
 */
abstract class Query[+E, +U]() extends NodeGenerator {

  def unpackable: Unpackable[_ <: E, _ <: U]
  lazy val reified = unpackable.reifiedNode
  lazy val linearizer = unpackable.linearizer

  def flatMap[F, T](f: E => Query[F, T]): Query[F, T] = {
    val fv = f(unpackable.value)
    new Bind[F, T](Node(this), Node(fv))(fv)
  }

  def map[F, T](f: E => F)(implicit unpack: Unpack[F, T]): Query[F, T] = flatMap(v => Query(f(v)))

  def >>[F, T](q: Query[F, T]): Query[F, T] = flatMap(_ => q)

  def filter[T, R](f: E => T)(implicit wt: CanBeQueryCondition[T], reify: Reify[E, R]): Query[R, U] =
    new Filter[R, U](Node(this), unpackable.reifiedUnpackable(reify), Node(wt(f(unpackable.value))))

  def withFilter[T, R](f: E => T)(implicit wt: CanBeQueryCondition[T], reify: Reify[E, R]) = filter(f)(wt, reify)

  def where[T <: Column[_], R](f: E => T)(implicit wt: CanBeQueryCondition[T], reify: Reify[E, R]) = filter(f)(wt, reify)

  /*
  def groupBy(by: Column[_]*) =
    new Query[E, U](unpackable, cond, modifiers ::: by.view.map(c => new Grouping(Node(c))).toList)

  def orderBy(by: Ordering*) = new Query[E, U](unpackable, cond, modifiers ::: by.toList)

  def exists = StdFunction[Boolean]("exists", map(_ => ConstColumn(1)))
  */

  def cond: Seq[NodeGenerator] = Nil //--
  def modifiers: List[QueryModifier] = Nil //--
  def typedModifiers[T <: QueryModifier]: List[T] = Nil //--

  /*
  // Unpackable queries only
  def union[O >: E, T >: U, R](other: Query[O, T]*)(implicit reify: Reify[O, R]) = wrap(Union(false, this :: other.toList))

  def unionAll[O >: E, T >: U, R](other: Query[O, T]*)(implicit reify: Reify[O, R]) = wrap(Union(true, this :: other.toList))

  def count = ColumnOps.CountAll(Subquery(this, false))

  def sub[UU >: U, R](implicit reify: Reify[E, R]) = wrap(this)

  private def wrap[R](base: Node)(implicit reify: Reify[E, R]): Query[R, U] = {
    def f[EE](unpackable: Unpackable[EE, _ <: U]) = unpackable.endoMap(v => v match {
      case t:AbstractTable[_] =>
        t.mapOp(_ => Subquery(base, false)).asInstanceOf[EE]
      case o =>
        var pos = 0
        val p = Subquery(base, true)
        unpackable.mapOp { v =>
          pos += 1
          SubqueryColumn(pos, p, v match {
            case c: Column[_] => c.typeMapper
            case SubqueryColumn(_, _, tm) => tm
            case _ => throw new SQueryException("Expected Column or SubqueryColumn")
          })
        }
    })
    val r: Unpackable[R, _ <: U] = unpackable.reifiedUnpackable(reify)
    Query[R, U](f(r))
  }

  //def reify[R](implicit reify: Reify[E, R]) =
  //  new Query[R, U](unpackable.reifiedUnpackable, cond, modifiers)

  // Query[Column[_]] only
  def asColumn(implicit ev: E <:< Column[_]): E = unpackable.value.asInstanceOf[WithOp].mapOp(_ => this).asInstanceOf[E]
  */

}

object Query extends PureNoAlias[Unit, Unit](Unpackable((), Unpack.unpackPrimitive[Unit])) {
  def apply[E, U](value: E)(implicit unpack: Unpack[E, U]) = apply[E, U](Unpackable(value, unpack))
  def apply[E, U](unpackable: Unpackable[_ <: E, _ <: U]): Query[E, U] =
    if(unpackable.reifiedNode.isNamedTable) new PureNoAlias[E, U](unpackable)
    else new Pure[E, U](unpackable.reifiedNode)(unpackable)
}

trait CanBeQueryCondition[-T] {
  def apply(value: T): Column[_]
}

object CanBeQueryCondition {
  implicit object BooleanColumnCanBeQueryCondition extends CanBeQueryCondition[Column[Boolean]] {
    def apply(value: Column[Boolean]) = value
  }
  implicit object BooleanOptionColumnCanBeQueryCondition extends CanBeQueryCondition[Column[Option[Boolean]]] {
    def apply(value: Column[Option[Boolean]]) = value
  }
  implicit object BooleanCanBeQueryCondition extends CanBeQueryCondition[Boolean] {
    def apply(value: Boolean) = new ConstColumn(value)(TypeMapper.BooleanTypeMapper)
  }
}

final case class Subquery(query: Node, rename: Boolean) extends UnaryNode {
  val child = Node(query)
  protected[this] override def nodeChildNames = Seq("query")
  protected[this] def nodeRebuild(child: Node): Node = copy(query = child)
  override def isNamedTable = true
}

final case class SubqueryColumn(pos: Int, subquery: Node, typeMapper: TypeMapper[_]) extends UnaryNode {
  val child = Node(subquery)
  protected[this] override def nodeChildNames = Seq("subquery")
  protected[this] def nodeRebuild(child: Node): Node = copy(subquery = child)
  override def toString = "SubqueryColumn c"+pos
}

final case class Union(all: Boolean, queries: IndexedSeq[Node]) extends SimpleNode {
  protected[this] def nodeChildGenerators = queries
  protected[this] def nodeRebuild(ch: IndexedSeq[Node]): Node = copy(queries = ch)
  override def toString = if(all) "Union all" else "Union"
}

class PureNoAlias[+E, +U](val unpackable: Unpackable[_ <: E, _ <: U]) extends Query[E, U] {
  override def nodeDelegate = reified
}

final case class Pure[+E, +U](value: Node)(_unpackable: Unpackable[_ <: E, _ <: U]) extends Query[E, U] with UnaryNode {
  def child = value
  val unpackable = _unpackable.endoMap(n => WithOp.mapOp(n, { x => Wrapped(Node(x), Node(this)) }))
  protected[this] override def nodeChildNames = Seq("value")
  protected[this] def nodeRebuild(child: Node): Node = copy[E, U](value = child)()
  override def isNamedTable = true
}

abstract class FilteredQuery[+E, +U] extends Query[E, U] with Node {
  def base: Unpackable[_ <: E, _ <: U]
  lazy val unpackable = base.endoMap(n => WithOp.mapOp(n, { x => Wrapped(Node(x), Node(this)) }))
  override def toString = "FilteredQuery:" + getClass.getName.replaceAll(".*\\.", "")
  override def isNamedTable = true
}

final case class GroupBy[+E, +U](from: Node, base: Unpackable[_ <: E, _ <: U], groupBy: Node) extends FilteredQuery[E, U] with BinaryNode {
  def left = from
  def right = groupBy
  protected[this] override def nodeChildNames = Seq("from", "groupBy")
  protected[this] def nodeRebuild(left: Node, right: Node): Node = copy[E, U](from = left, groupBy = right)
}

final case class Filter[+E, +U](from: Node, base: Unpackable[_ <: E, _ <: U], where: Node) extends FilteredQuery[E, U] with BinaryNode {
  def left = from
  def right = where
  protected[this] override def nodeChildNames = Seq("from", "where")
  protected[this] def nodeRebuild(left: Node, right: Node): Node = copy[E, U](from = left, where = right)
}

final case class Bind[+E, +U](from: Node, select: Node)(selectQ: Query[E, U]) extends Query[E, U] with BinaryNode {
  def left = from
  def right = select
  val unpackable = selectQ.unpackable.endoMap(n => WithOp.mapOp(n, { x => Wrapped(Node(x), Node(this)) }))
  protected[this] override def nodeChildNames = Seq("from", "select")
  protected[this] def nodeRebuild(left: Node, right: Node): Node = copy[E, U](from = left, select = right)()
  override def isNamedTable = true
}

final case class Wrapped(what: Node, in: Node) extends SimpleNode {
  protected[this] def nodeChildGenerators = Seq(what, in)
  protected[this] override def nodeChildNames = Seq("what", "in")
  protected[this] def nodeRebuild(ch: IndexedSeq[Node]): Node = copy(what = ch(0), in = ch(1))
}
