package example

object QuasiquoteInterpolation {
  final case class Select(repr: String)

  implicit final class QuasiquoteOps(private val ctx: StringContext)
      extends AnyVal {
    def q(args: Any*): Select =
      Select((ctx.parts ++ args.map(_.toString)).mkString)
  }

  val standardInterpolation = s"value"

  val quasiquoteInterpolation: Select = q"select"

  val bsonPkg = q"_root_.reactivemongo.api.bson"

  val typedQuasiquoteSplice: Select = q"${quasiquoteInterpolation}.member"

  val typedQuasiquoteSplice2: Select = q"${bsonPkg}.exceptions"
}
