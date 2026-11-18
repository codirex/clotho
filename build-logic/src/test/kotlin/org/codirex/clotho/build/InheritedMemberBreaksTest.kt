package org.codirex.clotho.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * JApiCmp reports a removed inherited member once per class and change type and does not name the
 * member. These tests pin that granularity so the limitation documented in
 * binary-compatibility/README.md ("Inherited members") stays visible.
 */
class InheritedMemberBreaksTest {
    @Test
    fun `inherited member removals are class level breaks identified by change type`() {
        val report =
            """
            <japicmp>
              <classes>
                <class fullyQualifiedName="org.example.Sub" changeStatus="MODIFIED" binaryCompatible="false" sourceCompatible="false">
                  <compatibilityChanges>
                    <compatibilityChange type="FIELD_REMOVED_IN_SUPERCLASS" binaryCompatible="false" sourceCompatible="false"/>
                    <compatibilityChange type="METHOD_REMOVED_IN_SUPERCLASS" binaryCompatible="false" sourceCompatible="false"/>
                  </compatibilityChanges>
                </class>
              </classes>
            </japicmp>
            """.trimIndent()

        val breaks = JApiCmpReport.readBreaks("clotho", report.byteInputStream(), "test-report.xml")

        assertEquals(
            setOf(
                BreakIdentity(
                    "clotho",
                    "org.example.Sub",
                    MemberKind.CLASS,
                    "",
                    emptyList(),
                    "",
                    "FIELD_REMOVED_IN_SUPERCLASS",
                ),
                BreakIdentity(
                    "clotho",
                    "org.example.Sub",
                    MemberKind.CLASS,
                    "",
                    emptyList(),
                    "",
                    "METHOD_REMOVED_IN_SUPERCLASS",
                ),
            ),
            breaks,
        )
    }
}
