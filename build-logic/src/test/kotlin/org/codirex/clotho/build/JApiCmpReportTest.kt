package org.codirex.clotho.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class JApiCmpReportTest {
    private fun breaks(xml: String): Set<BreakIdentity> =
        JApiCmpReport.readBreaks("clotho", xml.byteInputStream(), "test-report.xml")

    @Test
    fun `extracts every binary incompatible change with its exact owner`() {
        val expected =
            setOf(
                BreakIdentity("clotho", "org.example.Gone", MemberKind.CLASS, "", emptyList(), "", "CLASS_REMOVED"),
                BreakIdentity("clotho", "org.example.Api", MemberKind.CLASS, "", emptyList(), "", "CLASS_NOW_FINAL"),
                BreakIdentity(
                    "clotho",
                    "org.example.Api",
                    MemberKind.SUPERCLASS,
                    "org.example.Base",
                    emptyList(),
                    "",
                    "SUPERCLASS_REMOVED",
                ),
                BreakIdentity(
                    "clotho",
                    "org.example.Api",
                    MemberKind.INTERFACE,
                    "java.io.Closeable",
                    emptyList(),
                    "",
                    "INTERFACE_REMOVED",
                ),
                BreakIdentity(
                    "clotho",
                    "org.example.Api",
                    MemberKind.CONSTRUCTOR,
                    "<init>",
                    listOf("int"),
                    "",
                    "CONSTRUCTOR_REMOVED",
                ),
                BreakIdentity(
                    "clotho",
                    "org.example.Api",
                    MemberKind.METHOD,
                    "run",
                    listOf("java.lang.String", "long"),
                    "void",
                    "METHOD_RETURN_TYPE_CHANGED",
                ),
                BreakIdentity(
                    "clotho",
                    "org.example.Api",
                    MemberKind.METHOD,
                    "removedFlag",
                    emptyList(),
                    "boolean",
                    "METHOD_REMOVED",
                ),
                BreakIdentity(
                    "clotho",
                    "org.example.Api",
                    MemberKind.FIELD,
                    "LIMIT",
                    emptyList(),
                    "int",
                    "FIELD_TYPE_CHANGED",
                ),
            )

        assertEquals(expected, breaks(REPORT))
    }

    @Test
    fun `reports a change listed on both a method and its return type only once`() {
        val returnTypeChanges =
            breaks(REPORT).filter { it.name == "run" && it.change == "METHOD_RETURN_TYPE_CHANGED" }

        assertEquals(1, returnTypeChanges.size)
    }

    @Test
    fun `ignores changes that are binary compatible`() {
        val report =
            """
            <japicmp>
              <classes>
                <class fullyQualifiedName="org.example.Api" changeStatus="MODIFIED" binaryCompatible="true" sourceCompatible="false">
                  <compatibilityChanges>
                    <compatibilityChange type="METHOD_ADDED_TO_INTERFACE" binaryCompatible="true" sourceCompatible="false"/>
                  </compatibilityChanges>
                </class>
              </classes>
            </japicmp>
            """.trimIndent()

        assertTrue(breaks(report).isEmpty())
    }

    @Test
    fun `an empty report has no breaks`() {
        assertTrue(breaks("<japicmp><classes/></japicmp>").isEmpty())
    }

    @Test
    fun `rejects a document that is not a JApiCmp report`() {
        val error = assertThrows<IllegalArgumentException> { breaks("<report/>") }

        assertTrue(error.message!!.contains("not a JApiCmp XML report"), error.message)
    }

    @Test
    fun `refuses documents with a DOCTYPE`() {
        val error =
            assertThrows<IllegalArgumentException> {
                breaks("<!DOCTYPE japicmp [<!ENTITY x \"y\">]><japicmp/>")
            }

        assertTrue(error.message!!.contains("Cannot parse"), error.message)
    }

    private companion object {
        val REPORT =
            """
            <japicmp oldJar="old.jar" newJar="new.jar">
              <classes>
                <class fullyQualifiedName="org.example.Gone" changeStatus="REMOVED" binaryCompatible="false" sourceCompatible="false">
                  <compatibilityChanges>
                    <compatibilityChange type="CLASS_REMOVED" binaryCompatible="false" sourceCompatible="false"/>
                  </compatibilityChanges>
                </class>
                <class fullyQualifiedName="org.example.Api" changeStatus="MODIFIED" binaryCompatible="false" sourceCompatible="false">
                  <compatibilityChanges>
                    <compatibilityChange type="CLASS_NOW_FINAL" binaryCompatible="false" sourceCompatible="false"/>
                    <compatibilityChange type="ANNOTATION_ADDED" binaryCompatible="true" sourceCompatible="true"/>
                  </compatibilityChanges>
                  <superclass changeStatus="MODIFIED" superclassOld="org.example.Base" superclassNew="java.lang.Object" binaryCompatible="false" sourceCompatible="false">
                    <compatibilityChanges>
                      <compatibilityChange type="SUPERCLASS_REMOVED" binaryCompatible="false" sourceCompatible="false"/>
                    </compatibilityChanges>
                  </superclass>
                  <interfaces>
                    <interface fullyQualifiedName="java.io.Closeable" changeStatus="REMOVED" binaryCompatible="false" sourceCompatible="false">
                      <compatibilityChanges>
                        <compatibilityChange type="INTERFACE_REMOVED" binaryCompatible="false" sourceCompatible="false"/>
                      </compatibilityChanges>
                    </interface>
                  </interfaces>
                  <constructors>
                    <constructor name="Api" changeStatus="REMOVED" binaryCompatible="false" sourceCompatible="false">
                      <parameters>
                        <parameter changeStatus="REMOVED" type="int"/>
                      </parameters>
                      <compatibilityChanges>
                        <compatibilityChange type="CONSTRUCTOR_REMOVED" binaryCompatible="false" sourceCompatible="false"/>
                      </compatibilityChanges>
                    </constructor>
                  </constructors>
                  <methods>
                    <method name="run" changeStatus="MODIFIED" binaryCompatible="false" sourceCompatible="false">
                      <parameters>
                        <parameter changeStatus="UNCHANGED" type="java.lang.String"/>
                        <parameter changeStatus="UNCHANGED" type="long"/>
                      </parameters>
                      <returnType changeStatus="MODIFIED" oldValue="void" newValue="int" binaryCompatible="false" sourceCompatible="false">
                        <compatibilityChanges>
                          <compatibilityChange type="METHOD_RETURN_TYPE_CHANGED" binaryCompatible="false" sourceCompatible="false"/>
                        </compatibilityChanges>
                      </returnType>
                      <compatibilityChanges>
                        <compatibilityChange type="METHOD_RETURN_TYPE_CHANGED" binaryCompatible="false" sourceCompatible="false"/>
                      </compatibilityChanges>
                    </method>
                    <method name="removedFlag" changeStatus="REMOVED" binaryCompatible="false" sourceCompatible="false">
                      <returnType changeStatus="REMOVED" oldValue="boolean" newValue="n.a."/>
                      <compatibilityChanges>
                        <compatibilityChange type="METHOD_REMOVED" binaryCompatible="false" sourceCompatible="false"/>
                      </compatibilityChanges>
                    </method>
                  </methods>
                  <fields>
                    <field name="LIMIT" changeStatus="MODIFIED" binaryCompatible="false" sourceCompatible="false">
                      <type changeStatus="MODIFIED" oldValue="int" newValue="long"/>
                      <compatibilityChanges>
                        <compatibilityChange type="FIELD_TYPE_CHANGED" binaryCompatible="false" sourceCompatible="false"/>
                      </compatibilityChanges>
                    </field>
                  </fields>
                </class>
              </classes>
            </japicmp>
            """.trimIndent()
    }
}
