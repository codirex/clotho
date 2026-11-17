package org.codirex.clotho.build

import java.io.File
import java.io.InputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/** A DOM builder that refuses DOCTYPE declarations, external entities and XInclude. */
internal fun secureDocumentBuilder(): DocumentBuilder {
    val factory = DocumentBuilderFactory.newInstance()
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    factory.isXIncludeAware = false
    factory.isExpandEntityReferences = false
    return factory.newDocumentBuilder()
}

internal fun Element.childElements(): List<Element> {
    val result = ArrayList<Element>()
    var node: Node? = firstChild
    while (node != null) {
        if (node is Element) result += node
        node = node.nextSibling
    }
    return result
}

/**
 * Reads the machine-readable (XML) JApiCmp report and extracts every binary-incompatible change.
 *
 * Each `compatibilityChange` with `binaryCompatible="false"` becomes one [BreakIdentity], attached to
 * the nearest enclosing class, superclass, interface, constructor, method or field element. Nested
 * detail elements (return type, parameters, field type, annotations) belong to that owner.
 */
object JApiCmpReport {
    private const val NOT_AVAILABLE = "n.a."

    private class Owner(
        val kind: MemberKind,
        val name: String,
        val parameters: List<String>,
        val type: String,
    )

    fun readBreaks(artifact: String, report: File): Set<BreakIdentity> =
        report.inputStream().use { readBreaks(artifact, it, report.path) }

    fun readBreaks(artifact: String, input: InputStream, origin: String): Set<BreakIdentity> {
        val root =
            try {
                secureDocumentBuilder().parse(input).documentElement
            } catch (e: Exception) {
                throw IllegalArgumentException("Cannot parse JApiCmp report $origin: ${e.message}", e)
            }
        require(root.tagName == "japicmp") {
            "$origin is not a JApiCmp XML report (root element is <${root.tagName}>)."
        }

        val breaks = linkedSetOf<BreakIdentity>()
        for (classes in root.childElements().filter { it.tagName == "classes" }) {
            for (clazz in classes.childElements().filter { it.tagName == "class" }) {
                val className = clazz.getAttribute("fullyQualifiedName")
                require(className.isNotBlank()) { "$origin contains a <class> without fullyQualifiedName." }
                collect(artifact, className, clazz, Owner(MemberKind.CLASS, "", emptyList(), ""), breaks)
            }
        }
        return breaks
    }

    private fun collect(
        artifact: String,
        className: String,
        element: Element,
        owner: Owner,
        out: MutableSet<BreakIdentity>,
    ) {
        for (child in element.childElements()) {
            if (child.tagName == "compatibilityChange") {
                if (child.getAttribute("binaryCompatible") == "false") {
                    out +=
                        BreakIdentity(
                            artifact = artifact,
                            className = className,
                            kind = owner.kind,
                            name = owner.name,
                            parameters = owner.parameters,
                            type = owner.type,
                            change = child.getAttribute("type"),
                        )
                }
            } else {
                collect(artifact, className, child, ownerOf(child) ?: owner, out)
            }
        }
    }

    private fun ownerOf(element: Element): Owner? =
        when (element.tagName) {
            "superclass" ->
                Owner(
                    MemberKind.SUPERCLASS,
                    pick(element.getAttribute("superclassOld"), element.getAttribute("superclassNew")),
                    emptyList(),
                    "",
                )
            "interface" ->
                Owner(MemberKind.INTERFACE, element.getAttribute("fullyQualifiedName"), emptyList(), "")
            "constructor" -> Owner(MemberKind.CONSTRUCTOR, "<init>", parametersOf(element), "")
            "method" ->
                Owner(
                    MemberKind.METHOD,
                    element.getAttribute("name"),
                    parametersOf(element),
                    typeOf(element, "returnType"),
                )
            "field" ->
                Owner(MemberKind.FIELD, element.getAttribute("name"), emptyList(), typeOf(element, "type"))
            else -> null
        }

    private fun parametersOf(element: Element): List<String> =
        element
            .childElements()
            .filter { it.tagName == "parameters" }
            .flatMap { it.childElements() }
            .filter { it.tagName == "parameter" }
            .map { it.getAttribute("type") }

    /** The baseline type of a `returnType` or `type` child; the new type only if there is no old one. */
    private fun typeOf(element: Element, childName: String): String {
        val child = element.childElements().firstOrNull { it.tagName == childName } ?: return ""
        return pick(child.getAttribute("oldValue"), child.getAttribute("newValue"))
    }

    private fun pick(old: String, new: String): String =
        old.takeUnless { it.isBlank() || it == NOT_AVAILABLE }
            ?: new.takeUnless { it.isBlank() || it == NOT_AVAILABLE }
            ?: ""
}
