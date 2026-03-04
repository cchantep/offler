package io.github.offler

import scala.meta._

import scala.util.matching.Regex

import scala.meta.Enumerator
import scala.meta.tokens.Token

import _root_.metaconfig.{ Conf, Configured }
import _root_.scalafix.v1._

final class GoodCodeSyntax(
    finalCaseClass: Boolean,
    caseClassNoDefaultValues: Boolean,
    postfixExcludedQualifiers: Regex,
    forbiddenImports: Regex,
    singleTermBlockThreshold: Int)
    extends SyntacticRule("OfflerGoodCodeSyntax") {

  def this() = {
    this(
      caseClassNoDefaultValues = true,
      finalCaseClass = true,
      postfixExcludedQualifiers = GoodCodeSyntax.postfixExcludedQualifiers,
      forbiddenImports = GoodCodeSyntax.forbiddenImports,
      singleTermBlockThreshold = GoodCodeSyntax.singleTermBlockThreshold
    )
  }

  override def fix(
      implicit
      doc: SyntacticDocument
    ): Patch = fixTrees(List(doc.tree), List.empty, Patch.empty)

  @annotation.tailrec
  private def fixTrees(
      trees: List[Tree],
      globalImportRefs: List[Term.Ref],
      patch: Patch
    ): Patch =
    trees.headOption match {
      case Some(tree) => {
        val updated = tree match {
          case Importer(ref, importees) => {
            def refLint: Option[Patch] =
              globalImportRefs.find(_.text == ref.text).map { previous =>
                Patch.lint(DuplicateImporterRef(ref, previous))
              }

            patch + Patch.fromIterable(importees.collect {
              case importee
                  if GoodCodeSyntax.regexMatches(
                    forbiddenImports,
                    s"${ref.syntax}.${importee.syntax}"
                  ) =>
                Patch.lint(ForbiddenImport(ref, importee))
            }) + refLint
          }

          case cls @ Defn.Class.After_4_6_0(mods, nme, _, ctor, _)
              if (mods.exists(_.is[Mod.Case])) => {
            // rule#9 - Check case class definition

            val clsPatch: Patch = {
              // Ensure it's 'final'
              if (finalCaseClass && !mods.exists(_.is[Mod.Final])) {
                patch + Patch.addLeft(cls, "final ")
              } else {
                patch
              }
            }

            if (caseClassNoDefaultValues) {
              // Prevent default values in for the field definitions
              ctor.children.tail
                .flatMap(_.children)
                .filter {
                  _.tokens.exists(_.text == "=")
                }
                .foldLeft(clsPatch) { (p, field) =>
                  p + Patch.lint(CaseClassWithDefault(nme, field))
                }
            } else {
              clsPatch
            }
          }

          case t @ Term.Apply.After_4_6_0(
                sel @ Term.Select(qual, nme),
                Term.ArgClause(Seq(arg), _)
              )
              if ({
                val q = qual.text

                q != "this" && q != "super" && !GoodCodeSyntax.regexMatches(
                  postfixExcludedQualifiers,
                  q
                )
              } &&
                t.pos.startLine == t.pos.endLine &&
                sel.text.contains(".") && t.parent.exists {
                  case `if` @ Term.If.After_4_4_0(`t`, _, _, _) =>
                    `if`.tokens
                      .takeWhile(_.pos.start < t.pos.start)
                      .lastOption
                      .exists(_.text == "(")

                  case Term.Apply.After_4_6_0(`t`, Term.ArgClause(Seq(_), _)) =>
                    false

                  case x @ Term.ArgClause(Seq(`t`), _) =>
                    x.parent.exists {
                      case ap @ Term.Apply.After_4_6_0(_, _) =>
                        ap.pos.startLine == ap.pos.endLine

                      case _ =>
                        false
                    }

                  case blk @ Term.Block(_) =>
                    blk.parent.exists {
                      case Term.Interpolate(_, _, _) =>
                        true

                      case _ =>
                        false
                    }

                  case _ =>
                    false

                } && (arg match {
                  case Term.Block(_) | Term.Function.After_4_6_0(_, _) |
                      Term.AnonymousFunction(_) | Term.Assign(_, _) |
                      Term.Tuple(Seq(_, _)) |
                      Term.ApplyInfix.After_4_6_0(_, _, _, _) |
                      Term.Apply.After_4_6_0(
                        // this nested apply could be rewritten
                        Term.Select(_, _),
                        Term.ArgClause(Seq(_), _)
                      ) | Term.Repeated(_) | Term.New(_) =>
                    false

                  case _ =>
                    true
                })) => {
            // rule#3 - dot-syntax apply to space
            // e.g. if (str.contains(x)) ~> if (str contains x)
            val n = qual.tokens.size

            patch ++ sel.tokens.drop(n).headOption.flatMap {
              case dot if (dot.text == ".") => {
                val m = n + 1 + nme.tokens.size

                for {
                  open <- t.tokens.drop(m).headOption.filter(_.text == "(")
                  clse <- t.tokens.lastOption
                } yield Patch
                  .fromIterable(
                    Seq(
                      Patch.replaceToken(dot, " "),
                      Patch.replaceToken(open, " "),
                      Patch.replaceToken(clse, "")
                    )
                  )
                  .atomic
              }

              case _ =>
                None
            }
          }

          case GoodCodeSyntax.BlockArgClauseParen(parenToken) => {
            // rule#5 - redundant block definition as single arg
            // e.g test({ .. }) ~> test { .. }

            patch + tree.tokens.lastOption.map { end =>
              (Patch.replaceToken(parenToken, " ") + Patch.removeToken(
                end
              )).atomic
            }
          }

          case Term.Tuple(Seq(lhs, _)) => {
            // rule#4 - Tuple2
            def `->`(off: Int): Option[Patch] =
              tree.tokens
                .drop(off)
                .dropWhile(_.text.forall(_.isWhitespace))
                .drop(lhs.tokens.size)
                .headOption
                .collect {
                  case tok if (tok.syntax == ",") =>
                    Patch.replaceToken(tok, " ->")

                }

            tree.parent match {
              case Some(Term.ArgClause(args, _)) => {
                if (args.exists(_.syntax.indexOf("->") != -1)) {
                  // Prevent if there is '->' syntax in children terms
                  patch
                } else {
                  patch ++ `->`(0)
                }
              }

              case _ => {
                if (tree.syntax startsWith "(") {
                  patch ++ `->`(1)
                } else {
                  patch ++ `->`(0)
                }
              }
            }
          }

          // TODO: (x) => \n... ~> { (x) => \n... }
          // TODO: lint else { single <if> } ~> else if
          // TODO: if ((x ...)) ~> if (x ...)

          case Term.CasesBlock(cases) =>
            // #rule4_cases
            patch ++ cases
              .sliding(2)
              .collect {
                case (c @ Case(_, cond, body)) :: after :: Nil
                    if ((body.pos.endLine > c.pos.startLine || cond.exists(
                      _.pos.startLine > c.pos.startLine
                    )) && after.pos.startLine == (body.pos.endLine + 1 /* No blank line between*/ )) =>
                  after.pos.startLine
              }
              .flatMap { lineAfter =>
                tree.tokens.collectFirst {
                  case tok if tok.pos.startLine == lineAfter =>
                    Patch.addLeft(tok, "\n")
                }
              }
              .toSeq

          case blk @ Term.Block(_) if (blk.parent.exists {
                case Term.Block(`blk` :: Nil) =>
                  true

                case _ =>
                  false
              }) => {
            // #rule4_redundant_block_nesting
            patch + fixRedundantBlock(blk)
          }

          case Term.Block(Term.Block(_) :: Nil) =>
            // See #rule4_redundant_block_nesting
            patch

          case blk @ Term.Block(
                (body @ (Term.Select(_, _) | Term.Apply.After_4_6_0(_, _) |
                GoodCodeSyntax.NotUnitLit() | Term.Interpolate(_, _, _))) :: Nil
              ) if (tree.parent.exists {
                case Term.If.After_4_4_0(_, _, _, _) |
                    Term.Try.After_4_9_9(_, _, _) | Term.ArgClause(_, _) |
                    Term.Interpolate(_, _, _) | Term.For.After_4_9_9(_, _) =>
                  false

                case _ =>
                  true
              } && (GoodCodeSemantic.textSize(body) <= singleTermBlockThreshold)) =>
            patch + fixSingleTermBlock(blk, body)

          case Term.EnumeratorsBlock(enums) => {
            // rule#5 - multiline generator without surrounding { .. } or ( .. }
            val ps = enums.collect {
              case en @ Enumerator.Generator(
                    _,
                    body @ Term.If.After_4_4_0(_, _, _, _)
                  )
                  if (body.pos.endLine > body.pos.startLine &&
                    !body.tokens.headOption.exists {
                      case Token.LeftBrace() | Token.LeftParen() =>
                        true

                      case _ =>
                        false
                    }) =>
                /* Generator body is multiline but not surrounded by (..) or {..}; e.g.
                 _ <-
                   if (..) {
                     // ..
                   } else ..
                 */
                val firstLineIsWhite = body.tokens
                  .takeWhile(_.pos.endLine == en.pos.startLine)
                  .forall {
                    case Token.Whitespace() => true
                    case _                  => false
                  }

                val afterLeftArrow = en.tokens.dropWhile {
                  case Token.LeftArrow() =>
                    false

                  case _ =>
                    true
                }.dropWhile {
                  case Token.LeftArrow() =>
                    true

                  case _ =>
                    false
                }.dropWhile {
                  case Token.EOL() =>
                    false

                  case Token.Whitespace() =>
                    true

                  case _ =>
                    false
                }

                for {
                  leftArrow <- afterLeftArrow.headOption
                  last <- afterLeftArrow.lastOption
                } yield {
                  val indent = Seq.fill(en.pos.startColumn)(' ').mkString
                  val endPatch = Patch.addRight(last, s"\n${indent}}")

                  if (firstLineIsWhite) {
                    Patch.addLeft(leftArrow, " {") + endPatch
                  } else {
                    Patch.addLeft(leftArrow, s"{\n$indent") + indentPatch(
                      body.tokens,
                      "  ",
                      List.empty
                    ) + endPatch
                  }
                }
            }.flatten

            patch ++ ps
          }

          case GoodCodeSyntax.SimpleYield(yld) if (yld.headOption.exists {
                case Token.LeftParen() | Token.LeftBracket() =>
                  true

                case _ =>
                  false
              }) =>
            // rule#5 - yield with surrounding ( .. ) or [ .. ] but no block body; e.g. for (x <- xs) yield (x + 1)
            patch + Patch.removeTokens((yld.takeWhile {
              case Token.LeftParen() | Token.LeftBrace() | Token.Whitespace() =>
                true

              case _ =>
                false
            } ++ yld.reverse.takeWhile {
              case Token.RightParen() | Token.RightBrace() |
                  Token.Whitespace() =>
                true

              case _ =>
                false
            }.reverse))

          case `if` @ Term.If.After_4_4_0(_, _, _, _) if {
                tree.pos.startLine < tree.pos.endLine && !tree.parent.exists {
                  case Term.If.After_4_4_0(_, _, _, _) =>
                    // `else if` already handled by parent `if`
                    true

                  case _ =>
                    false
                }
              } => {
            // rule#5 - multiline if without surrounding { .. } or ( .. }
            patch + fixMultilineIf(`if`)
          }

          case GoodCodeSyntax.NonSingleCaseBlockNoParen(arrow, body) => {
            // rule#5 - non-single case block without surrounding { .. } or ( .. }
            val indent = Seq.fill(body.pos.startColumn - 2)(' ').mkString
            val prefix: String = {
              if (body.pos.startLine == arrow.pos.startLine) {
                " {\n"
              } else {
                " {"
              }
            }

            patch + Patch.addRight(arrow, prefix) + Patch.addRight(
              body,
              s"\n${indent}}"
            )
          }

          case GoodCodeSyntax.Lambda(x, params, ps, body) => {
            // rule#5 - single parameter list
            // e.g. seq.map(x => { ... })

            val bodyPatch: Option[Patch] = body.tokens.headOption.collect {
              case start if start.text == "{" =>
                val p1 = Patch.removeToken(start)

                p1 ++ body.tokens.lastOption.collect {
                  case end if end.text == "}" =>
                    Patch.removeToken(end)
                }
            }

            val lambdaPatch: Option[Patch] = for {
              start <- x.tokens.headOption.filter(_.text == "(")
              toks = x.tokens.drop(1).dropWhile(_.text != "=>")
              whsp = toks.drop(1).takeWhile { tok =>
                val t = tok.text
                t != "\n" && t.trim.isEmpty
              }
              end <- x.tokens.lastOption.filter(_.text == ")")
            } yield (Patch.removeToken(start) ++ whsp.map(
              Patch.removeToken
            ) + Patch.removeToken(end))

            val paramPatch: Option[Patch] = {
              if (ps.size != 1) {
                None
              } else {
                for {
                  start <- params.tokens.headOption.filter(_.text == "(")
                  end <- params.tokens.lastOption.filter(_.text == ")")
                } yield (Patch.removeToken(start) + Patch.removeToken(end))
              }
            }

            patch + (
              Patch.addLeft(
                x,
                " { "
              ) + bodyPatch ++ lambdaPatch + paramPatch + Patch.addRight(x, "}")
            ).atomic
          }

          case blk @ Term.Block(body) => { //  | Template(_, _, _, _)
            // rule#6 - multiline block
            val nonTerm = blk.tokens.collect {
              case tok @ Token.Comment(_) =>
                (tok.pos.startLine, "comment") -> tok

              case tok
                  if (tok.len > 0 &&
                    tok.text.trim.isEmpty && tok.pos.startColumn == 0) =>
                (tok.pos.startLine, "initialWhitespace") -> tok

            }.toMap

            // Add line break for multiline definition inside the block
            val updatedPatch = body.zipWithIndex
              .foldLeft(
                Tuple4(
                  blk.pos,
                  Option.empty[String],
                  Option.empty[Patch],
                  patch
                )
              ) {
                case ((prevPos, prevKind, prevPatch, p), (blkElmt, idx)) =>
                  val kind: String = blkElmt match {
                    case Defn.Def.After_4_6_0(_, _, None, _, _) |
                        Defn.Val(_, _, _, _) =>
                      // Consider `val` and no-arg `def` the same way
                      "val"

                    case _ =>
                      blkElmt.getClass.getSimpleName
                  }

                  // rule#6

                  /*
                   * Current block element is just after the previous one,
                   * just with a line break between, but no blank line to space them more.
                   */
                  val lineBreakBefore =
                    blkElmt.pos.startLine == prevPos.endLine + 1

                  val isMultiline = blkElmt.tokens.count(
                    _.text == "\n"
                  ) > 1 || (blkElmt.pos.startLine < blkElmt.pos.endLine)

                  /*
                   * thisBlkElmt(..) // has a comment just after it
                   */
                  val commentAtEnd =
                    nonTerm.get(blkElmt.pos.endLine -> "comment")

                  val updPrevPatch: Option[Patch] = {
                    if (isMultiline) {
                      /* The block element is multiline, e.g.
                       *
                       * {
                       *   val thisElementIsMultiline = {
                       *     // ...
                       *     foo
                       *   }
                       *   nextElement(thisElementIsMultiline)
                       * }
                       */

                      commentAtEnd match {
                        case Some(comment)
                            if (
                              // the comment is after the end of the block body
                              comment.pos.startColumn > blkElmt.pos.endColumn
                            ) =>
                          /* #multiline_block_element2 e.g.
                           *
                           * {
                           *   val thisElementIsMultiline = {
                           *     // ...
                           *     foo
                           *   } // Some comment after
                           *   nextElement(thisElementIsMultiline)
                           */
                          Some(Patch.addRight(comment, "\n"))

                        case _ =>
                          Some(Patch.addRight(blkElmt, "\n"))
                      }
                    } else {
                      None
                    }
                  }

                  val up: Patch = prevPatch match {
                    case Some(prevPatch) if lineBreakBefore =>
                      p + prevPatch

                    case _ =>
                      if (idx > 0 && isMultiline && lineBreakBefore) {
                        nonTerm.get(
                          blkElmt.pos.startLine -> "initialWhitespace"
                        ) match {
                          case Some(whitespace) =>
                            p + Patch.addLeft(whitespace, "\n")

                          case None =>
                            p
                        }
                      } else if (
                        // rule#8
                        prevKind.exists(
                          _ != kind
                        ) && prevPos.startLine == prevPos.endLine &&
                        blkElmt.pos.startLine >= (prevPos.endLine + 1)
                      ) {
                        val prefix = // block tokens after the end of last block term
                          blk.tokens.dropWhile(_.pos.end <= prevPos.end)

                        // block tokens after end of last block and before start of current
                        // (non-terms: comment, whitespace, ...)
                        val between =
                          prefix.takeWhile(_.pos.end < blkElmt.pos.start)

                        between
                          .find(_.text.trim.nonEmpty)
                          .flatMap { tok =>
                            // tok: some non-whitespace token between
                            // previous and current terms

                            if (tok.pos.startLine <= (prevPos.endLine + 1)) {
                              // there is no whitespace line before `tok`
                              // (and after end of previous term)

                              blk.tokens
                                .takeWhile(_.pos.startLine != tok.pos.startLine)
                                .lastOption
                                .filterNot {
                                  // there is line space after
                                  _.pos.endLine < (blkElmt.pos.startLine - 1)
                                }
                            } else {
                              Option.empty[Token]
                            }
                          }
                          .orElse {
                            if (lineBreakBefore) {
                              prefix.headOption
                            } else {
                              Option.empty[Token]
                            }
                          } match {
                          case Some(tok) =>
                            // Add line break after previous term of different kind
                            p + Patch.addRight(tok, "\n")

                          case _ =>
                            p
                        }
                      } else {
                        p
                      }
                  }

                  Tuple4(
                    commentAtEnd.map(_.pos).getOrElse(blkElmt.pos),
                    Some(kind),
                    updPrevPatch,
                    up
                  )
              }
              ._4

            updatedPatch + fixIntermediaryDef(blk, body)
          }

          case _ =>
            patch
        }

        fixTrees(
          tree.children ++: trees.tail,
          globalImportRefs ++ (tree match {
            case Importer(ref, _) if tree.parent.flatMap(_.parent).exists {
                  case Pkg.Body(_) =>
                    true

                  case _ =>
                    false
                } =>
              Some(ref)

            case _ =>
              None
          }),
          updated
        )
      }

      case None =>
        patch
    }

  private def fixIntermediaryDef(
      blk: Term.Block,
      body: Seq[Stat]
    ): Option[Patch] =
    body match {
      case _ :: _ :: _ => {
        val end = body.reverse

        end.headOption.flatMap {
          case nme @ Term.Name(_) =>
            end
              .drop(1)
              .headOption
              .filter { term =>
                if (term.pos.endLine >= (nme.pos.startLine - 1)) {
                  // <term> is just before `nme` (no line between)
                  true
                } else {
                  val nonWhite = blk.tokens
                    .dropWhile(_.pos.start <= term.pos.end)
                    .takeWhile(_.pos.end < nme.pos.start)
                    .filter {
                      case Token.Whitespace() =>
                        false

                      case _ =>
                        true
                    }

                  nonWhite.isEmpty
                }
              }
              .flatMap { term =>
                val defToks: Option[Tokens] = term match {
                  case d @ Defn.Def.After_4_6_0(_, n, None, None, _)
                      if (n.text == nme.text) =>
                    Some(d.tokens.dropWhile {
                      case Token.KwDef() =>
                        false

                      case _ =>
                        true
                    })

                  case d @ Defn.Val(_, Pat.Var(n) :: Nil, None, _)
                      if (n.text == nme.text) =>
                    Some(d.tokens.dropWhile {
                      case Token.KwVal() =>
                        false

                      case _ =>
                        true
                    })

                  case _ =>
                    None
                }

                val defPatch: Option[Patch] = defToks.flatMap { toks =>
                  toks.find {
                    case Token.Equals() =>
                      true

                    case _ =>
                      false
                  }.map { eq =>
                    val beforeEq = toks.takeWhile {
                      _.pos.start <= eq.pos.start
                    }

                    val whitespaceAfterEq = toks.dropWhile {
                      _.pos.start <= eq.pos.start
                    }.takeWhile {
                      case Token.Whitespace() =>
                        true

                      case _ =>
                        false
                    }

                    Patch.removeTokens(beforeEq ++ whitespaceAfterEq)
                  }
                }

                defPatch.map {
                  _ + Patch.removeTokens(
                    blk.tokens
                      .dropWhile(_.pos.end <= term.pos.end)
                      .takeWhile(_.pos.end <= nme.pos.end)
                  )
                }
              }

          case _ =>
            None
        }
      }

      case _ =>
        None

    }

  @annotation.tailrec
  private def indentPatch(
      in: Seq[Token],
      indentInc: String,
      acc: List[Seq[Token]]
    ): Patch = {
    if (in.isEmpty) {
      Patch.fromIterable(acc.flatMap { line =>
        if (line.exists(_.text.trim.nonEmpty)) {
          line.headOption.map(Patch.addLeft(_, indentInc))
        } else {
          None
        }
      }.reverse)
    } else {
      (in.span {
        case Token.EOL() | Token.EOF() =>
          false

        case _ =>
          true
      }) match {
        case (line, Seq()) =>
          indentPatch(Nil, indentInc, line :: acc)

        case (line, rem) =>
          indentPatch(rem.drop(1), indentInc, line :: acc)
      }
    }
  }

  /**
   * Add parenthesis arround the `then` term of an `if` one.
   *
   * @param ifIndent (already takes care of `needsExtraIndent`)
   * @param needsExtraIndent needs extra indent from the fix of the parent
   */
  private def thenPatch(
      ifTree: Term.If,
      ifIndent: String,
      needsExtraIndent: Boolean
    ): Patch = {
    val afterLeftParen = ifTree.tokens.dropWhile {
      case Token.KwIf() => true
      case _            => false
    }.dropWhile(_.text.trim.isEmpty).dropWhile {
      case Token.LeftParen() => true
      case _                 => false
    }

    afterLeftParen
      .takeWhile(_.pos.end < ifTree.thenp.pos.start)
      .reverseIterator
      .find {
        case Token.RightParen() => true
        case _                  => false
      } match {
      case None =>
        Patch.empty

      case Some(condRightParen) => {
        val ifToks = afterLeftParen.dropWhile(_.pos.end <= condRightParen.end)

        val prepared = ifToks.dropWhile {
          case Token.Space() => true
          case _             => false
        }

        // More indent inside <then> body
        val oneliner: Boolean =
          ifTree.cond.pos.endLine == ifTree.thenp.pos.startLine

        val thenIndent = oneliner && (prepared.headOption.exists(
          _.text != "{"
        ) || ifTree.thenp.tokens.lastOption.exists(_.text != "}"))

        def addOpeningBracket: Patch = prepared.headOption.map { tok =>
          val prefix: String = {
            if (thenIndent) {
              /* e.g. if (<cond>) <then> */
              s"{\n${ifIndent}  "
            } else if (prepared.size == ifToks.size) {
              " {"
            } else {
              "{"
            }
          }

          Patch.addLeft(tok, prefix)
        }.getOrElse(Patch.empty)

        val toBeIndented: Seq[Token] = {
          if (needsExtraIndent || thenIndent) {
            prepared.dropWhile {
              case Token.EOL() | Token.EOF() =>
                false

              case _ =>
                true
            }.dropWhile {
              case Token.EOL() | Token.EOF() =>
                true

              case _ =>
                false
            }.takeWhile(_.pos.end <= ifTree.thenp.pos.end)
          } else {
            Seq.empty
          }
        }

        lazy val thenIndentIncrement: Option[String] = {
          if (needsExtraIndent && thenIndent) {
            Some("    ")
          } else if (needsExtraIndent || thenIndent) {
            Some("  ")
          } else {
            None
          }
        }

        val thenBodyPatch: Option[Patch] = for {
          start <- prepared.headOption
          end <- ifTree.thenp.tokens.lastOption
          patch <- {
            if (start.text != "{" || end.text != "}") {
              // No opening bracket so patch to surround <then> with { .. }
              Some(addOpeningBracket + Patch.addRight(end, s"\n${ifIndent}}"))
            } else if (ifTree.pos.startLine == ifTree.thenp.pos.endLine) {
              // <then> with brackets on same line: if (..) { .. }
              Some(
                Patch.addRight(start, s"\n$ifIndent  ") + Patch.removeTokens(
                  prepared.tail.takeWhile(
                    _.text.trim.isEmpty
                  ) ++ ifTree.thenp.tokens.reverse
                    .drop(1 /* } */ )
                    .takeWhile(_.text.trim.isEmpty)
                ) + Patch.addLeft(end, s"\n${ifIndent}")
              )
            } else {
              Option.empty[Patch]
            }
          }
        } yield patch

        (thenIndentIncrement.fold(Patch.empty)(
          indentPatch(toBeIndented, _, Nil)
        ) + thenBodyPatch).atomic
      }
    }
  }

  /**
   * @param tree the parent `if` (in case of `if (..) .. else if (..) ...`)
   * @param prevThenAndElse the previous <then> and the `else` body
   * @param ifIndent (includes needsExtraIndent)
   * @param needsExtraIndent needs extra indent for the whole `if`
   */
  private def elsePatch(
      tree: Term.If,
      prevThenAndElse: (Tree, Tree),
      ifIndent: String,
      needsExtraIndent: Boolean
    ): Patch = {
    import prevThenAndElse.{ _1 => prevThen, _2 => els }

    val afterPrevThen = tree.tokens.dropWhile(
      // "move" to after end of previous if's then term
      _.pos.end <= prevThen.pos.end
    )

    def removeWhitespaceBeforeElse: Patch = {
      if (prevThen.pos.endLine == els.pos.startLine) {
        // .. if (..)[\n]*<then> else ..
        Patch.empty
      } else {
        Patch.removeTokens(
          afterPrevThen.takeWhile {
            case Token.KwElse() =>
              // whitespace between then and `else`
              false

            case _ =>
              true
          }.dropRight(
            1 // keep one space between previous `}` and `else`
          )
        )
      }
    }

    val afterElse =
      afterPrevThen.dropWhile(_.text.trim.isEmpty).dropWhile {
        case Token.KwElse() =>
          // whitespace between <then> and `else`
          true

        case _ =>
          false
      }

    val elseBodyPatch: Option[Patch] = for {
      end <- els.tokens.lastOption
      start <- els.tokens.headOption
      after <- afterElse.headOption
    } yield {
      val bodyIndentPatch: Patch = {
        if (start.pos.startLine == end.pos.endLine || !needsExtraIndent) {
          // `else` was a oneliner
          Patch.empty
        } else {
          val indentInc: String = {
            if (start.pos.startLine == end.pos.endLine && needsExtraIndent) {
              "    "
            } else if (
              start.pos.startLine == end.pos.endLine || needsExtraIndent
            ) {
              "  "
            } else {
              ""
            }
          }

          indentPatch(
            els.tokens.dropWhile {
              case Token.EOL() | Token.EOF() =>
                false

              case _ =>
                true
            },
            indentInc,
            Nil
          )
        }
      }

      val indentElse = start.text != "{" || end.text != "}"

      val startPatch: Patch = {
        if (indentElse) {
          // Add missing { .. } to `else`

          if (after.pos.startLine == start.pos.startLine) {
            Patch.addLeft(start, s"{\n${ifIndent}  ")
          } else {
            Patch.addLeft(after, " {") + {
              if (!needsExtraIndent) {
                Patch.empty
              } else {
                Patch.addRight(after, "  ")
              }
            }
          }
        } else {
          // `else` has already { .. }
          Patch.empty
        }
      }

      def endPatch: Patch = {
        if (indentElse) {
          Patch.addRight(end, s"\n${ifIndent}}")
        } else {
          Patch.empty
        }
      }

      (startPatch + bodyIndentPatch + endPatch).atomic
    }

    removeWhitespaceBeforeElse + elseBodyPatch
  }

  private def decreasedIndentTokensPerEachLine(
      tokens: Seq[Token],
      indents: Int
    ): Seq[Token] = {
    val toRemovePerLine = indents * 2

    tokens.filter {
      case Token.EOL() =>
        false

      case Token.Whitespace() =>
        true

      case _ =>
        false

    }.groupBy(_.pos.startLine).toSeq.flatMap {
      case (_, toks) => toks.take(toRemovePerLine)
    }
  }

  /* Rule#5:
   {
     {
       // ...
     }
   }
   */
  private[offler] def fixRedundantBlock(blk: Term.Block): Patch =
    (blk.parent.filter {
      case Term.Block(_) => true
      case _             => false
    }) match {
      case Some(parent) if blk.tokens.headOption.exists {
            case Token.LeftBrace() =>
              true

            case _ =>
              false
          } => {
        val sinceLeftBrace = parent.tokens.dropWhile {
          case Token.LeftBrace() =>
            false

          case _ =>
            true
        }

        val untilRightBrace = parent.tokens.reverse.dropWhile {
          case Token.RightBrace() =>
            false

          case _ =>
            true
        }

        val beforeBlock = sinceLeftBrace.takeWhile(_.pos.start < blk.pos.start)
        val afterBlock = untilRightBrace.takeWhile(_.pos.end > blk.pos.end)

        if (
          (beforeBlock ++ afterBlock).exists {
            case Token.Comment(_) => true
            case _                => false
          }
        ) {
          /* If there is a comment in the nesting, keep as is; e.g.
           {
             // A comment
             {
               ...
             }
           }
           */
          Patch.empty
        } else {
          // HERE: Removing line content without removing associated \n so it ends with \n\n with \n around in the parent?
          Patch.removeTokens(
            beforeBlock ++ decreasedIndentTokensPerEachLine(
              blk.tokens.dropWhile(_.pos.startLine == blk.pos.startLine /* ignore first block line with '{' */ ),
              1
            ) ++ afterBlock
          )
        }
      }

      case _ =>
        Patch.empty
    }

  /* Rule#5 */
  private def fixSingleTermBlock(blk: Term.Block, body: Stat): Patch = {
    val inner = blk.tokens.drop(1).dropRight(1)

    lazy val before = inner.takeWhile {
      case Token.LeftParen() | Token.LeftBrace() | Token.Whitespace() =>
        true

      case _ =>
        false
    }

    lazy val beforeNoSpace = before.filter {
      case Token.Whitespace() =>
        false

      case _ =>
        true
    }

    @annotation.tailrec
    def go(in: Seq[Token], open: Seq[Token], out: List[Token]): List[Token] =
      in.headOption match {
        case Some(tok @ Token.Whitespace()) =>
          go(in.tail, open, tok :: out)

        case Some(tok) => {
          val expected: Option[String] = open.headOption.collect {
            case Token.LeftParen() =>
              ")"

            case Token.LeftBrace() =>
              "}"
          }

          if (expected.isEmpty) {
            out.reverse
          } else if (expected contains tok.text) {
            go(in.tail, open.tail, tok :: out)
          } else {
            // No matching expected
            Nil
          }
        }

        case None =>
          out.reverse
      }

    def surroundingBraces: Seq[Token] =
      blk.tokens.headOption.toSeq ++ blk.tokens.lastOption

    // Only whitespace
    def hasComment = inner.exists {
      case tok @ Token.Comment(_) =>
        tok.pos.end <= body.pos.start || tok.pos.startLine > body.pos.endLine

      case _ =>
        false
    }

    def spaceInCase = blk.parent.flatMap {
      case c @ Case(_, _, _) =>
        c.tokens.find(_.pos.start == blk.pos.start - 1)

      case _ =>
        None
    }

    def bodyFurtherIndent: Seq[Token] = {
      if (before.nonEmpty && body.pos.endLine > body.pos.startLine) {
        decreasedIndentTokensPerEachLine(
          body.tokens.dropWhile(_.pos.startLine == body.pos.startLine),
          indents = 1
        )
      } else {
        Seq.empty
      }
    }

    if (hasComment) {
      Patch.empty
    } else if (beforeNoSpace.isEmpty) {
      if (before.isEmpty) {
        Patch.empty
      } else if (spaceInCase.nonEmpty) {
        val after = go(inner.reverse, beforeNoSpace, Nil)

        Patch.removeTokens(spaceInCase ++ surroundingBraces ++ after)
      } else {
        Patch.removeTokens(
          (surroundingBraces ++ before ++ bodyFurtherIndent ++ inner.reverse.takeWhile {
            case Token.Whitespace() =>
              true

            case _ =>
              false
          })
        )
      }
    } else {
      val after = go(inner.reverse, beforeNoSpace, Nil)

      Patch.removeTokens(
        (spaceInCase ++ surroundingBraces ++ before ++ bodyFurtherIndent ++ after)
      )
    }
  }

  /* Rule#5 */
  private def fixMultilineIf(tree: Term.If): Patch = {
    @annotation.tailrec
    def go(
        in: Seq[Tree],
        branches: Seq[Term.If],
        multiline: Boolean
      ): (Seq[Term.If], Option[(Tree, Tree)], Boolean) =
      in.headOption match {
        case Some(ifTree @ Term.If.After_4_4_0(_ /* cond */, th, els, _)) => {
          val updBranches = ifTree +: branches

          lazy val afterElse = ifTree.tokens.reverse.takeWhile {
            case Token.KwElse() =>
              false

            case _ =>
              true
          }.reverse

          val updMulti = multiline || (
            // End line of <then> is after start line of `if`
            ifTree.pos.startLine < th.pos.endLine
          ) || afterElse.headOption
            .map(_.pos.startLine)
            .zip(afterElse.lastOption.map(_.pos.endLine))
            .exists {
              case (startLine, endLine) =>
                endLine > startLine
            }

          if (els.children.isEmpty) {
            (updBranches.reverse, Some(th -> els), updMulti)
          } else {
            els match {
              case Term.If.After_4_4_0(_, _, _, _) =>
                go(els +: in.tail, updBranches, updMulti)

              case _ =>
                (
                  updBranches.reverse,
                  Some(th -> els),
                  updMulti || els.pos.startLine < els.pos.endLine
                )
            }
          }
        }

        case _ =>
          // Should not happen
          (branches.reverse, None, multiline)
      }

    val surrounding: Option[(Int, Patch)] = tree.parent.flatMap {
      // When multiline `if-...` as body of a def without {..} block,
      // add missing brackets

      case d @ (Defn.Val(_, _, _, _) | Defn.Def.After_4_6_0(_, _, None, _, _))
          if (
            tree.pos.endLine > tree.pos.startLine
          ) => {
        for {
          start <- d.tokens.find(_.text == "=")
          end <- d.tokens.lastOption
        } yield Tuple3(d.pos.startLine -> d.pos.startColumn, start, end)
      }

      case d @ Defn.Def.After_4_6_0(_, _, Some(args), _, _)
          if (
            tree.pos.endLine > tree.pos.startLine
          ) => {
        for {
          start <- d.tokens.dropWhile(_.pos.end <= args.pos.end).find {
            case Token.Equals() => true
            case _              => false
          }
          end <- d.tokens.lastOption
        } yield Tuple3(start.pos.startLine -> d.pos.startColumn, start, end)
      }

      case _ =>
        None
    }.map {
      case ((startLine, startCol), start, end) =>
        val indent = Seq.fill(startCol)(' ').mkString
        val prefix: String = {
          if (startLine == tree.pos.startLine) {
            // merge indent with previous space between `=` and `if` (e.g. `=_if`)
            val i = indent.drop(1)

            s"= {\n$i  "
          } else {
            "= {"
          }
        }

        startLine -> (Patch.replaceToken(start, prefix) + Patch.addRight(
          end,
          s"\n$indent}"
        )).atomic
    }

    val branchPatch: Seq[Patch] = go(
      Seq(tree),
      Seq.empty,
      tree.thenp.pos.endLine > tree.pos.startLine
    ) match {
      case (branches, els, true) => {
        val startCol = tree.parent.collect {
          case p if p.pos.startLine == tree.pos.startLine =>
            /* e.g. val _ = if (..) .. */
            p.pos.startColumn
        }.getOrElse(tree.pos.startColumn)

        val baseIndent = Seq.fill(startCol)(' ').mkString

        val needsExtraIndent: Boolean =
          surrounding.exists(
            _._1 == tree.pos.startLine
          ) && tree.tokens.headOption.exists {
            case Token.KwIf() =>
              true

            case _ => false
          }

        val ifIndent: String = {
          if (needsExtraIndent) {
            s"$baseIndent  "
          } else {
            baseIndent
          }
        }

        branches.map(thenPatch(_, ifIndent, needsExtraIndent)) ++ els.map(
          elsePatch(tree, _, ifIndent, needsExtraIndent)
        )
      }

      case _ =>
        Seq.empty[Patch]
    }

    Patch.fromIterable(branchPatch ++ surrounding.map(_._2))
  }

  override def withConfiguration(config: Configuration): Configured[Rule] = {
    val conf: Conf =
      config.conf.get[Conf]("Offler").getOrElse(Conf.fromMap(Map.empty))

    val caseClassNoDefaultValues =
      conf.get[Boolean]("caseClassNoDefaultValues").getOrElse(true)

    val finalCaseClass = conf.get[Boolean]("finalCaseClass").getOrElse(true)

    val postfixExcludedQualifiers: Regex =
      conf
        .get[Seq[String]]("postfixExcludedQualifiers")
        .map {
          _.mkString("(", "|", ")").r
        }
        .getOrElse(GoodCodeSyntax.postfixExcludedQualifiers)

    val forbiddenImports: Regex =
      conf
        .get[Seq[String]]("forbiddenImports")
        .map {
          _.mkString("(", "|", ")").r
        }
        .getOrElse(GoodCodeSyntax.forbiddenImports)

    val singleTermBlockThreshold: Int =
      conf
        .get[Int]("singleTermBlockThreshold")
        .getOrElse(GoodCodeSyntax.singleTermBlockThreshold)

    Configured.ok(
      new GoodCodeSyntax(
        caseClassNoDefaultValues,
        finalCaseClass,
        postfixExcludedQualifiers,
        forbiddenImports,
        singleTermBlockThreshold
      )
    )
  }

  override val isRewrite = true
  override val isLinter = true
}

case class CaseClassWithDefault(cls: Type.Name, `val`: Tree)
    extends Diagnostic {
  override val categoryID = "caseClassWithDefault"

  override def position: Position = `val`.pos

  override def message: String =
    s"Case class ${cls} defined with default value"

  override def explanation: String =
    "Remove default values from case class parameters to keep construction " +
      "explicit and avoid surprising behavior."
}

case class ForbiddenImport(
    ref: Term.Ref,
    importee: Importee)
    extends Diagnostic {
  override val categoryID = "forbiddenImport"

  override def position: Position = importee.pos

  override def message: String =
    s"Forbidden import ${ref.syntax}.${importee.syntax}"

  override def explanation: String =
    "This import matches a forbidden pattern. Remove it or replace it with an " +
      "allowed import."
}

case class DuplicateImporterRef(ref: Term.Ref, previous: Term.Ref)
    extends Diagnostic {
  override val categoryID = "duplicateImporterRef"

  override def position: Position = ref.pos

  override def message: String =
    s"Imports from ${ref.text} must be merged with previous one at line ${previous.pos.startLine}"

  override def explanation: String =
    "Merge imports from the same qualifier into a single import statement to " +
      "reduce duplication and improve readability."
}

object GoodCodeSyntax {
  val postfixExcludedQualifiers = "[A-Z].?".r

  val forbiddenImports = "example\\.Forbidden\\..*".r

  private[offler] def regexMatches(regex: Regex, value: String): Boolean =
    regex.pattern.matcher(value).matches()

  val singleTermBlockThreshold = 50

  // ---

  object Lambda {

    def unapply(
        tree: Tree
      ): Option[(Term.ArgClause, Term.ParamClause, List[Term.Param], Term.Block)] =
      tree match {
        case Term.Apply.After_4_6_0(
              _,
              x @ Term.ArgClause(
                Seq(
                  Term.Function.After_4_6_0(
                    params @ Term.ParamClause(ps, _),
                    body @ Term.Block(_ :: _)
                  )
                ),
                _
              )
            ) if (tree.parent.collect {
              case Term.Apply.After_4_6_0(_, _) => true
            }.isEmpty) =>
          // rule#5 - single parameter list
          // e.g. seq.map(x => { ... })
          Some((x, params, ps, body))

        case Term.Apply.After_4_6_0(
              Term.Apply.After_4_6_0(_ /*fn*/, _ /*firstParamList*/ ),
              x @ Term.ArgClause(
                Seq(
                  Term.Function.After_4_6_0(
                    params @ Term.ParamClause(ps, _),
                    body @ Term.Block(_ :: _)
                  )
                ),
                _
              )
            ) if (tree.parent.collect {
              case Term.Apply.After_4_6_0(_, _) => true
            }.isEmpty) =>
          // rule#5 - two parameter list;
          // e.g. seq.foldLeft(z)((a, b) => { ... })
          Some((x, params, ps, body))

        case _ =>
          None
      }
  }

  object BlockArgClauseParen {

    def unapply(tree: Tree): Option[Token] = tree match {
      case Term.ArgClause(Seq(b @ Term.Block(_)), _)
          if (
            // #ignoreEmptyBlock: Skip empty block e.g Future.successful({}) or Success({})
            b.children.nonEmpty && tree.parent.exists {
              case Term.Apply.After_4_6_0(_, _) =>
                // #ignoreNew: Skip new Foo({ .. })
                true

              case _ => false
            }
          ) =>
        tree.tokens.headOption.filter(_.text == "(")

      case _ =>
        None
    }
  }

  object NonSingleCaseBlockNoParen {

    def unapply(tree: Tree): Option[(Token, Term.Block)] = tree match {
      case Case(pat, cond, body @ Term.Block(_))
          if (tree.parent.exists(_.children.size > 1)) =>
        cond
          .getOrElse(pat)
          .tokens
          .lastOption
          .flatMap { off =>
            tree.tokens
              .dropWhile(_ != off)
              .drop(1)
              .dropWhile {
                case Token.FunctionArrow() =>
                  false

                case _ =>
                  true
              }
              .headOption
          }
          .collect {
            case arrow if (body.tokens.headOption.exists(_.text != "{")) =>
              arrow -> body
          }

      case _ =>
        None
    }
  }

  object SimpleYield {

    def unapply(tree: Tree): Option[Tokens] = tree match {
      case Term.ForYield.After_4_9_9(
            _,
            (Term.Select(_, _) | Term.Name(_) | NotUnitLit())
          ) =>
        Some(tree.tokens.dropWhile {
          case Token.KwYield() =>
            false

          case _ =>
            true
        }.drop(1 /* yield */ ).dropWhile {
          case Token.Whitespace() =>
            true

          case _ =>
            false
        })

      case _ =>
        None
    }
  }

  object NotUnitLit {

    def unapply(tree: Tree): Boolean = tree match {
      case Lit(v) =>
        v == null || !v.isInstanceOf[Unit]

      case _ =>
        false
    }
  }
}
