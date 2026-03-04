package io.github.offler

import scala.meta._
import scala.meta.tokens.Token

import scala.util.matching.Regex

import _root_.metaconfig.{ Conf, Configured }
import _root_.scalafix.v1._

final class GoodCodeSemantic(
    forbiddenApplies: Regex,
    noTypeBlockThreshold: Int)
    extends SemanticRule("OfflerGoodCodeSemantic") {

  def this() = this(
    forbiddenApplies = "__".r,
    noTypeBlockThreshold = GoodCodeSemantic.noTypeBlockThreshold
  )

  override def fix(
      implicit
      doc: SemanticDocument
    ): Patch =
    fixTrees(List(doc.tree), Patch.empty)

  @annotation.tailrec
  private def fixTrees(
      trees: List[Tree],
      patch: Patch
    )(implicit
      doc: SemanticDocument
    ): Patch = {
    val NonTrivalDefNoType =
      new GoodCodeSemantic.NonTrivalDefNoTypeExtractor(noTypeBlockThreshold)

    val DefWithTypeTrivalBody =
      new GoodCodeSemantic.DefWithTypeTrivalBodyExtractor(noTypeBlockThreshold)

    trees.headOption match {
      case Some(tree) => {
        val updated = tree match {
          case nme @ Term.Name(_) if {
                val call =
                  GoodCodeSemantic.prettySymbol(nme.symbol).takeWhile(_ != '(')

                GoodCodeSyntax.regexMatches(forbiddenApplies, call)
              } =>
            // rule#1
            tree.parent match {
              case Some(
                    Term.Eta(_) | Term.Select(_, _) |
                    Term.Apply.After_4_6_0(_, _)
                  ) =>
                patch + Patch.lint(ForbiddenApply(nme, nme.symbol))

              case _ =>
                patch
            }

          case NonTrivalDefNoType(_) => {
            // rule#10
            val pos = tree.children.dropWhile {
              case Mod.Annot(_) => true
              case _            => false
            }.headOption.map(_.pos).getOrElse(tree.pos)

            patch + Patch.lint(MissingDeclarationType(tree, pos))
          }

          case DefWithTypeTrivalBody(tpeAscript) =>
            // rule#10
            patch + Patch.fromIterable(tpeAscript.map(Patch.removeToken))

          case _ =>
            patch
        }

        fixTrees(tree.children ++: trees.tail, updated)
      }

      case None =>
        patch
    }
  }

  override def withConfiguration(config: Configuration): Configured[Rule] = {
    val conf: Conf =
      config.conf.get[Conf]("Offler").getOrElse(Conf.fromMap(Map.empty))

    val forbiddenApplies: Regex =
      conf
        .get[Seq[String]]("forbiddenApplies")
        .map(_.mkString("(", "|", ")").r)
        .getOrElse(GoodCodeSemantic.forbiddenApplies)

    val noTypeBlockThreshold: Int =
      conf
        .get[Int]("noTypeBlockThreshold")
        .getOrElse(GoodCodeSemantic.noTypeBlockThreshold)

    Configured.ok(new GoodCodeSemantic(forbiddenApplies, noTypeBlockThreshold))
  }

  override val isRewrite = true
  override val isLinter = false
}

case class ForbiddenApply(term: Term, sym: Symbol) extends Diagnostic {
  override val categoryID = "forbiddenApply"

  override def position: Position = term.pos

  override def message: String = {
    val repr = GoodCodeSemantic.prettySymbol(sym)

    s"Forbidden apply: $repr"
  }

  override def explanation: String =
    "Applying forbidden methods is disallowed by this rule. " +
      "Use an allowed method or refactor the call so it does not match the " +
      "configured forbidden pattern."
}

case class MissingDeclarationType(
    defn: Tree,
    override val position: Position)
    extends Diagnostic {
  override val categoryID = "missingDeclType"

  override def message: String = defn match {
    case Defn.Def.After_4_6_0(_, _, _, None, _) =>
      "Missing explicit return type"

    case _ =>
      "Missing explicit declaration type"
  }

  override def explanation: String =
    "Add an explicit type annotation to clarify the declaration type and " +
      "make the API contract stable across refactors."
}

object GoodCodeSemantic {
  val forbiddenApplies: Regex = "example\\.Forbidden\\.forbidden.*".r

  private[offler] def prettySymbol(sym: Symbol): String =
    sym.toString.map {
      case '/' => '.'
      case '#' => '.'
      case chr => chr
    }

  private[offler] def textSize(tree: Tree): Int = tree.tokens.foldLeft(0) {
    case (sz, Token.Whitespace()) =>
      sz

    case (sz, tok) =>
      sz + tok.text.size
  }

  val noTypeBlockThreshold = 30

  // Rule#10
  class NonTrivalDefNoTypeExtractor(
      threshold: Int // TODO: higher limit for <apply>?
    )(implicit
      doc: SemanticDocument) {

    def unapply(tree: Tree): Option[Tree] =
      unapplyDefnBody(tree).filter(accepts)

    private def unapplyDefnBody(tree: Tree): Option[Tree] = tree match {
      case Defn.Def.After_4_6_0(_, _, _, None, body) =>
        Some(body)

      case Defn.Val(_, _, None, body) =>
        Some(body)

      case _ =>
        None
    }

    private val accepts: Tree => Boolean = { tree: Tree =>
      val isTypeMember = tree.parent.exists {
        case Template.Body(_, _) =>
          true // is type member

        case _ =>
          false
      }

      tree match {
        case Lit(_) | Term.New(_) | Term.Name(_) | Term.NewAnonymous(_) =>
          // #ignoreTrivial; e.g. "lit" | new Foo(..) | ref | new Foo { .. }
          false

        case Term.Select(_, _) =>
          // #ignoreSelect
          textSize(tree) > threshold

        case Term.Interpolate(_, _, _) =>
          // #ignoreStringInterpolation
          !tree.symbol.info.exists(_.toString.indexOf(": String") != -1)

        case Term.Apply.After_4_6_0(term, _) => {
          // #ignoreCompanionApply; e.g. Seq(..)
          val companionApply = term.symbol.info.exists { nfo =>
            val nme = term.syntax.takeWhile(_ != '[')
            val sign =
              s"${term.symbol.toString} => val method ${nme}: ${nme}.type"

            sign == nfo.toString
          }

          !companionApply && isTypeMember
        }

        case Term.ApplyType.After_4_6_0(app, _) =>
          accepts(app)

        case _ =>
          isTypeMember || {
            textSize(tree) > threshold
          }
      }
    }
  }

  // Rule #10
  class DefWithTypeTrivalBodyExtractor(threshold: Int) {

    def unapply(tree: Tree): Option[Tokens] = tree match {
      case Defn.Def.After_4_6_0(
            _,
            _,
            None,
            Some(tpe),
            body @ Term.Interpolate(_, _, _)
          ) if (tpe.text.endsWith("String") && textSize(body) < threshold) =>
        typeAscription(tree)

      case Defn.Def.After_4_6_0(
            _,
            _,
            None,
            Some(_),
            body @ Lit(_)
          ) if (textSize(body) < threshold) =>
        typeAscription(tree)

      case Defn.Val(_, _, Some(_), body @ (Lit(_) | Term.Interpolate(_, _, _)))
          if (textSize(body) < threshold) =>
        typeAscription(tree)

      case _ =>
        None
    }

    private def typeAscription(tree: Tree): Option[Tokens] = {
      val ascript = tree.tokens.dropWhile {
        case Token.Colon() =>
          false

        case _ =>
          true
      }.takeWhile {
        case Token.Equals() =>
          false

        case _ =>
          true
      }

      ascript.lastOption.collect {
        case Token.Whitespace() =>
          ascript.dropRight(1)

      }
    }
  }
}
