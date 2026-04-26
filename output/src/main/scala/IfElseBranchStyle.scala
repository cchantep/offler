package example

object IfElseBranchStyle {
  def warn(hasWarningsDisabled: Boolean): String => Unit = {
    if (hasWarningsDisabled) {
      (_: String) => ()
    } else {
      println(_: String)
    }
  }
}
