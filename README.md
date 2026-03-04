# Offler

Scalafix rules to standardize Scala code style and semantics for data-oriented codebases. The project provides both syntactic rewrites and semantic checks to keep code consistent and safe.

## Setup

Add the Scalafix rules artifact to your build:

```sbt
ThisBuild / libraryDependencies += "io.github.cchantep" %% "offler-rules" % "<version>"
```

Ensure the Scalafix SBT plugin is enabled in `project/plugins.sbt`:

```sbt
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.5")
```

Then adjust your `.scalafix.conf`:

```conf
rules = [
  OfflerGoodCodeSyntax,
  OfflerGoodCodeSemantic
]
```

## Usage

    sbt scalafix

## Rules

(Rule #1) Calls matching forbidden apply targets must be avoided. See [Configuration: `forbiddenApplies`](#forbiddenapplies) to declare project-specific forbidden apply target regexes. This is useful to enforce a consistent, owner-qualified API style (for example with Spark SQL helpers), and prevent ambiguous shortcuts.

```scala
import org.apache.spark.sql.{ functions => F }

// Forbidden when `forbiddenApplies` contains `org\\.apache\\.spark\\.sql\\.functions\\.column`
val c1 = F.column("id")

// Preferred: consistently namespaced helper call
val c2 = F.col("id")
```

(Rule #2) Import paths matching forbidden patterns must be avoided. See [Configuration: `forbiddenImports`](#forbiddenimports) to declare project-specific forbidden import regexes.

```scala
// Forbidden when `forbiddenImports` matches this path
import org.apache.spark.sql.functions._

// Preferred: explicit namespace alias
import org.apache.spark.sql.{ functions => F }
```

(Rule #3) When it's possible, nested call to single argument function (`Function1`) should be written without `.` and `(..)`, to limit `(..)` nesting that leads to bad readability. See [Configuration: `postfixExcludedQualifiers`](#postfixexcludedqualifiers) to exclude specific qualifiers from this rewrite/check logic.

```scala
if (mySeq.contains(elem)) { ... } // `contains` call nested in `if (..)` ..

// should be rewritten as:

if (mySeq contains elem) { ... }
```

> When not nested the previous rule mustn't be applied:

```scala
val elemContained = mySeq.contains(elem)

// must not be rewritten as:
val elemContained = mySeq contains elem
```

(Rule #4) For a similar constraint to avoid too much nested parenthesis, `Tuple2` should be written using the `a -> b` syntax, rather than `(a, b)`.

```scala
Map((key, value))
Success(())

// should be rewritten as:

Map(key -> value)
Success({})
```

(Rule #5) Unnecessary block parenthesis or curly braces for single expression should also be avoid.

```scala
val foo = {
  MyBar(...)
}

// should be rewritten as:

val foo = MyBar(...)
```

As counterpart, multiline block should be surrounded by/not omit parenthesis.

```scala
x match {
  case Foo() =>
    line1
    line2

  // ...
}

// should be rewritten as

x match {
  case Foo() => {
    line1
    line2
  }

  // ...
}
```

In case or coupled blocks and/or statements, mainly for `if`/`else`, the same style must be applied for both. If `if` or `else` requires surrounding `{ ... }` according this rule, then both must comply with that.

```scala
if (x) res1
else {
  val x = y + z
  check(x)
}

// should be rewritten with same block style for if/else

if (x) {
  res1
} else {
  val x = y + z
  check(x)
}
```

(Rule #6) Most of the time, multiline blocks and/or statements (except inside call parameters) must be separated by a blank line from surrounding other blocks and statements.

```scala
val foo = callX(
  arg1 = bar,
  arg2 = lorem(1)
)
val ipsum = callY(1)

// should be rewritten

val foo = callX(
  arg1 = bar,
  arg2 = lorem(1)
)

val ipsum = callY(1)
```

(Rule #7) Except for readability edge-cases, do not use unnecessary intermediate value.

```scala
def bad(): T = {
  val bar = doSomething()
  bar
}

def good(): T = doSomething()
```

(Rule #8) As far as it's pragmatic, "groups" of line in a same block must be separated by a single blank line.

```scala
val foo = "bar"
val lorem = "ipsum"
doSomething(foo, lorem)

// should be rewritten by spacing declaration a subsequent call

val foo = "bar"
val lorem = "ipsum"

doSomthing(foo, lorem)
```

(Rule #9) Data model types (mainly case class) must avoid default values. See [Configuration: `caseClassNoDefaultValues`](#caseclassnodefaultvalues) to control this check.

```scala
case class Foo(score: Int = 0)

// must be

case class Foo(score: Int) // with default value in the process according context
```

(Rule #10) Type member definitions (except for literal), and also local definitions if not trivial, should explicitly declare (return) type. See [Configuration: `noTypeBlockThreshold`](#notypeblockthreshold) to tune when explicit type annotations become required.

```scala
object Example {
  def wrong = Seq("foo", "bar")

  def ok: Seq[String] = Seq("foo", "bar")
}
```

## Configuration

Some of these rules can be configured using Scalafix settings.

```conf
Offler {
  # Enforce no default values in case class params.
  caseClassNoDefaultValues = true

  # Qualifiers excluded from postfix rewrite by regex.
  postfixExcludedQualifiers = ["[A-Z].?"]

  # Import patterns to forbid (Spark conventions)
  forbiddenImports = [
    "org\\.apache\\.spark\\.sql\\.functions\\..+",
    "org\\.apache\\.spark\\.sql\\.SQLImplicits\\.StringToColumn\\.\\$"
  ]

  # Apply targets to forbid (by fully qualified name).
  forbiddenApplies = [
    "org\\.apache\\.spark\\.sql\\.functions\\.column"
  ]

  # Minimum block size to require explicit types.
  noTypeBlockThreshold = 80
}
```

### `caseClassNoDefaultValues`

Type: `Boolean`  
Default: `true`

Controls whether default parameter values are forbidden in case classes.

Use this setting when you want constructor usage to always be explicit and avoid hidden defaults that can make call sites ambiguous.

Example configuration:

```conf
Offler {
  caseClassNoDefaultValues = true
}
```

Example:

```scala
// Reported when enabled
final case class User(id: String, active: Boolean = true)

// Accepted
final case class User(id: String, active: Boolean)
```

### `postfixExcludedQualifiers`

Type: `List[String]` (regex patterns)  
Default: `[]`

Defines qualifier patterns that are excluded from postfix rewrite checks.

Use this when postfix rewrites are generally desired but should not apply to specific qualifiers (for example, names matching internal DSL or naming conventions).

Each entry is a regular expression. If a qualifier matches one of these patterns, rewrite/check logic is skipped for that case.

Example configuration:

```conf
Offler {
  postfixExcludedQualifiers = ["[A-Z].?"]
}
```

In this example, qualifiers that start with an uppercase letter are excluded.

Example:

```scala
val DF = spark.table("events")
val df = spark.table("events")

// Excluded from dot-to-infix rewrite because qualifier "DF" matches [A-Z].?
if (DF.contains("id")) println("found")

// Still checked/rewritten because qualifier "df" does not match [A-Z].?
if (df.contains("id")) println("found")
```

### `forbiddenImports`

Type: `List[String]` (regex patterns)  
Default: `[]`

Lists import patterns that are rejected by semantic checks.

Use this to ban broad or unsafe imports and guide developers toward approved alternatives.

This is especially useful for patterns such as `import org.apache.spark.sql.functions._`. In many Spark codebases, a common convention is to use an alias import like `import org.apache.spark.sql.{ functions => F }` and then call helpers as `F.col`, `F.when`, `F.lit`, etc. Preventing wildcard imports helps ensure calls stay consistently namespaced with their owner, which improves readability and avoids accidental symbol collisions.

Each entry is matched against the fully qualified imported symbol path.

Example configuration:

```conf
Offler {
  forbiddenImports = [
    "org\\.apache\\.spark\\.sql\\.functions\\..+",
    "org\\.apache\\.spark\\.sql\\.SQLImplicits\\.StringToColumn\\.\\$"
  ]
}
```

Example:

```scala
// Reported with the pattern above
import org.apache.spark.sql.functions._

// Preferred style: explicit namespace alias
import org.apache.spark.sql.{ functions => F }

val c = F.col("id")
```

### `forbiddenApplies`

Type: `List[String]` (regex patterns)  
Default: `[]`

Lists fully qualified apply targets that should be rejected when called.

Use this to block specific API entry points that are known to be problematic in your codebase.

Each entry is matched against the fully qualified function/method target resolved by semantic analysis.

Example configuration:

```conf
Offler {
  forbiddenApplies = [
    "org\\.apache\\.spark\\.sql\\.functions\\.column"
  ]
}
```

Example:

```scala
import org.apache.spark.sql.{ functions => F }

// Reported when the target is forbidden (as `F.col` would be preferred)
val c = F.column("id")
```

### `noTypeBlockThreshold`

Type: `Int`  
Default: `80`

Sets the minimum body size threshold beyond which explicit type annotations are required.

Size is expressed as the number of non-whitespace characters in the body expression (computed from token text, ignoring spaces and newlines).

Use a lower value to enforce more explicit typing in shorter expressions, or a higher value to keep inference for compact code while requiring explicit types once the expression text grows.

Example configuration:

```conf
Offler {
  noTypeBlockThreshold = 80
}
```

Example:

```scala
// Typical intent when threshold is exceeded:
// prefer explicit type annotations for vals/defs with large blocks.
val transformed: Dataset[Row] = {
  // complex multi-line logic
  ???
}
```
