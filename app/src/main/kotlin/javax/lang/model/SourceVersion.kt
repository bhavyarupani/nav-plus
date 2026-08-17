package javax.lang.model

/**
 * Minimal compatibility shim for `javax.lang.model.SourceVersion`, which is part of the JDK's
 * `java.compiler` module - a module Android does not ship at all (confirmed via a real on-device
 * `NoClassDefFoundError`, not a config/dependency issue - this is a third, distinct GraphHopper/
 * Android incompatibility alongside the VarHandle and Janino ones documented on
 * [com.roadpulse.auto.engine.GraphHopperRoutingEngine]).
 *
 * GraphHopper 7.0's `IntEncodedValueImpl.isValidEncodedValue` calls exactly one method from this
 * class - `isKeyword(CharSequence)` - to reject encoded-value names that collide with a Java
 * keyword or literal. This class supplies only that method, implementing the fixed, documented
 * list of Java reserved words and literals from the Java Language Specification (a fact, not
 * copyrightable expression - no JDK implementation code is reproduced here).
 */
object SourceVersion {
    private val KEYWORDS =
        hashSetOf(
            "abstract",
            "continue",
            "for",
            "new",
            "switch",
            "assert",
            "default",
            "goto",
            "package",
            "synchronized",
            "boolean",
            "do",
            "if",
            "private",
            "this",
            "break",
            "double",
            "implements",
            "protected",
            "throw",
            "byte",
            "else",
            "import",
            "public",
            "throws",
            "case",
            "enum",
            "instanceof",
            "return",
            "transient",
            "catch",
            "extends",
            "int",
            "short",
            "try",
            "char",
            "final",
            "interface",
            "static",
            "void",
            "class",
            "finally",
            "long",
            "strictfp",
            "volatile",
            "const",
            "float",
            "native",
            "super",
            "while",
            "true",
            "false",
            "null",
            "_",
        )

    @JvmStatic
    fun isKeyword(s: CharSequence): Boolean = KEYWORDS.contains(s.toString())
}
