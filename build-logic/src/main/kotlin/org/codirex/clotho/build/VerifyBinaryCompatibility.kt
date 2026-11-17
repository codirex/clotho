package org.codirex.clotho.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Enforces the approved-break manifest on the unfiltered JApiCmp report of one artifact. */
abstract class VerifyBinaryCompatibility : DefaultTask() {
    @get:Input abstract val artifact: Property<String>

    /** The release being built (`VERSION_NAME`); only manifest entries for it are eligible. */
    @get:Input abstract val targetRelease: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val japicmpReport: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    @get:OutputFile abstract val summary: RegularFileProperty

    @TaskAction
    fun verify() {
        BinaryCompatibilityGate.verify(
            artifact = artifact.get(),
            targetRelease = targetRelease.get(),
            report = japicmpReport.get().asFile,
            manifest = manifest.get().asFile,
            summary = summary.get().asFile,
        )
    }
}
