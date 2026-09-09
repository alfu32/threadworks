package com.threadwork.app

import com.threadwork.compiler.api.CompilerOptions
import com.threadwork.compiler.api.CompilerPlugin
import com.threadwork.compiler.c.CCompiler
import com.threadwork.compiler.filesystem.FilesystemCompiler
import com.threadwork.compiler.generated.nodejs.JSCompiler
import com.threadwork.compiler.generic.CompilerCompiler
import com.threadwork.compiler.generic.GenericCompiler
import com.threadwork.compiler.go.GoCompiler
import com.threadwork.compiler.naivekotlin.NaiveKotlinCompiler
import com.threadwork.compiler.php.PhpCompiler
import com.threadwork.compiler.quickjs.QuickJsCompiler
import com.threadwork.core.diagnostics.DiagnosticSeverity
import com.threadwork.core.model.effectiveTechnologyId
import com.threadwork.core.model.projectName
import com.threadwork.core.model.rootNode
import com.threadwork.core.validation.DocumentValidator
import com.threadwork.app.ui.defaultPluginsFolder
import com.threadwork.app.ui.launchDesktopApp
import com.threadwork.app.ui.loadCompilerPlugins
import com.threadwork.storage.KotlinxJsonDocumentStore
import java.nio.file.Path
import kotlin.io.path.createDirectories

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        null, "help", "--help", "-h" -> printHelp()
        "desktop" -> parseDesktopArguments(args.drop(1)).let { desktop ->
            launchDesktopApp(desktop.pluginsFolder, desktop.initialFile)
        }
        "validate" -> validate(args)
        "compile" -> compile(args)
        else -> {
            System.err.println("Unknown command: ${args.first()}")
            printHelp()
        }
    }
}

private fun printHelp() {
    println(
        """
        Threadwork CLI

        Commands:
          desktop [--plugins <dir>] [file.orch]
                                              Open a project in the graphical editor; create it when missing
          validate <file.orch> [--plugins <dir>]
                                              Validate document references and compiler plugins
          compile <file.orch> <dir> [--plugins <dir>]
                                              Export with the first matching compiler plugin
        """.trimIndent(),
    )
}

private data class DesktopArguments(
    val pluginsFolder: Path?,
    val initialFile: Path?,
)

private fun parseDesktopArguments(args: List<String>): DesktopArguments {
    val index = args.indexOfFirst { it == "--plugins" || it == "--plugins-dir" }
    val pluginsFolder = if (index < 0) null else args.getOrNull(index + 1)?.let(Path::of)
        ?: error("Usage: desktop [--plugins <dir>] [file.orch]")
    val positionals = positionalArgs(args)
    require(positionals.size <= 1) { "Usage: desktop [--plugins <dir>] [file.orch]" }
    return DesktopArguments(pluginsFolder, positionals.singleOrNull()?.let(Path::of))
}

private fun validate(args: Array<String>) {
    val commandArgs = args.drop(1)
    val positionals = positionalArgs(commandArgs)
    val file = positionals.getOrNull(0)?.let(Path::of) ?: error("Usage: validate <file.orch> [--plugins <dir>]")
    val pluginsFolder = parsePluginsFolderOrDefault(commandArgs)
    val document = KotlinxJsonDocumentStore().load(file)
    val compilers = compilersFrom(pluginsFolder)
    val compiler = selectCompiler(document, compilers)
        ?: error("No compiler plugin supports ${file.fileName}")
    val diagnostics = compiler.validate(document)
    diagnostics.forEach { println("${it.severity}: ${it.message}") }
    if (diagnostics.none { it.severity == DiagnosticSeverity.Error }) {
        println("Document is valid")
    }
}

private fun compile(args: Array<String>) {
    val commandArgs = args.drop(1)
    val positionals = positionalArgs(commandArgs)
    val file = positionals.getOrNull(0)?.let(Path::of) ?: error("Usage: compile <file.orch> <dir> [--plugins <dir>]")
    val output = positionals.getOrNull(1)?.let(Path::of) ?: error("Usage: compile <file.orch> <dir> [--plugins <dir>]")
    val pluginsFolder = parsePluginsFolderOrDefault(commandArgs)
    val document = KotlinxJsonDocumentStore().load(file)
    val compilers = compilersFrom(pluginsFolder)
    val compiler = selectCompiler(document, compilers)
        ?: error("No compiler plugin supports ${file.fileName}")
    val result = compiler.compile(document, CompilerOptions(projectName = document.projectName(), compilerPlugins = compilers))
    result.diagnostics.forEach { println("${it.severity}: ${it.message}") }
    val generatedProject = result.generatedProject
    if (!result.success || generatedProject == null) error("Compilation failed")
    output.createDirectories()
    generatedProject.writeTo(output)
    println("Wrote ${generatedProject.files.size} files to ${output.toAbsolutePath()}")
}

private fun compilersFrom(pluginsFolder: Path): List<CompilerPlugin> =
    loadCompilerPlugins(pluginsFolder) + listOf(
        CompilerCompiler(),
        FilesystemCompiler(),
        GenericCompiler(),
        NaiveKotlinCompiler(),
        JSCompiler(),
        QuickJsCompiler(),
        PhpCompiler(),
        GoCompiler(),
        CCompiler(),
    )

private fun selectCompiler(document: com.threadwork.core.model.ThreadworkDocument, compilers: List<CompilerPlugin>): CompilerPlugin? {
    val root = document.rootNode()
    val requestedCompilerId = root.technology.compilerId.trim()
    val requestedTechnologyId = document.effectiveTechnologyId(root.id)
    val supporting = compilers.filter { compiler -> runCatching { compiler.supports(document) }.getOrDefault(false) }
    return supporting.filterIsInstance<FilesystemCompiler>().firstOrNull { FilesystemCompiler.shouldAggregate(document) } ?:
        supporting.firstOrNull { it.id == requestedCompilerId } ?:
        supporting.firstOrNull { requestedTechnologyId.isNotBlank() && requestedTechnologyId in it.supportedTechnologyIds } ?:
        supporting.firstOrNull { requestedTechnologyId.isNotBlank() && it.providedTechnologies.any { tech -> tech.technologyId == requestedTechnologyId } } ?:
        supporting.firstOrNull()
}

private fun parsePluginsFolderOrDefault(args: List<String>): Path {
    val index = args.indexOfFirst { it == "--plugins" || it == "--plugins-dir" }
    if (index < 0) return defaultPluginsFolder()
    return args.getOrNull(index + 1)?.let(Path::of) ?: error("Missing plugins directory after ${args[index]}")
}

private fun positionalArgs(args: List<String>): List<String> {
    val result = mutableListOf<String>()
    var index = 0
    while (index < args.size) {
        when (args[index]) {
            "--plugins",
            "--plugins-dir" -> index += 2
            else -> {
                result += args[index]
                index += 1
            }
        }
    }
    return result
}
