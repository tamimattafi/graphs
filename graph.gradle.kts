tasks.register("generateDependencyGraphDOT") {
    group = "graphs"

    doLast {
        generateDotGraph()
    }
}

tasks.register("generateDependencyGraphPNG") {
    group = "graphs"

    doLast {
        val graphFile = generateDotGraph()
        "dot -Tpng -O ${graphFile.absolutePath}".runCommand()
        graphFile.delete()
    }
}

fun generateDotGraph(): File {
    val dotFile = provideDefaultGraphFile(resetIfExists = true)
    val dotWriter = dotFile.writer().apply {
        write("digraph {\n")
        write("  graph [label=\"${rootProject.name}\n \",labelloc=t,fontsize=30,ranksep=1.4];\n")
        write("  node [style=filled, fillcolor=\"#bbbbbb\"];\n")
        write("rankdir=TB;\n")
    }

    val rootProjects = mutableListOf<Project>()
    var queue = mutableListOf(rootProject)
    while (queue.isNotEmpty()) {
        val project = queue.removeAt(0)
        rootProjects.add(project)
        queue.addAll(project.childProjects.values)
    }

    var projects = LinkedHashSet<Project>()
    val dependencies = LinkedHashMap<Pair<Project, Project>, List<String>>()
    val multiplatformProjects = mutableListOf<Project>()
    val jsProjects = mutableListOf<Project>()
    val androidProjects = mutableListOf<Project>()
    val javaProjects = mutableListOf<Project>()

    queue = mutableListOf(rootProject)
    while (queue.isNotEmpty()) {
        val project = queue.removeAt(0)
        queue.addAll(project.childProjects.values)

        if (project.plugins.hasPlugin("org.jetbrains.kotlin.multiplatform")) {
            multiplatformProjects.add(project)
        }
        if (project.plugins.hasPlugin("org.jetbrains.kotlin.js")) {
            jsProjects.add(project)
        }
        if (project.plugins.hasPlugin("com.android.library") || project.plugins.hasPlugin("com.android.application")) {
            androidProjects.add(project)
        }
        if (project.plugins.hasPlugin("java-library") || project.plugins.hasPlugin("java")) {
            javaProjects.add(project)
        }

        project.configurations.forEach { config ->
            config.dependencies
                .withType(ProjectDependency::class.java)
                .map(ProjectDependency::getDependencyProject)
                .forEach { dependency ->
                    projects.add(project)
                    projects.add(dependency)
                    rootProjects.remove(dependency)

                    if (project == dependency) return@forEach

                    val graphKey = Pair(project, dependency)
                    val traits = dependencies.computeIfAbsent(graphKey) {
                        emptyList()
                    }.toMutableList()

                    if (config.name.toLowerCase().endsWith("implementation")) {
                        traits.add("style=dotted")
                    }
                }
        }
    }

    projects = LinkedHashSet(projects.sortedBy(Project::getPath))

    dotWriter.write("\n  # Projects\n\n")
    for (project in projects) {
        val traits = mutableListOf<String>()

        if (multiplatformProjects.contains(project)) {
            traits.add("fillcolor=\"#ffd2b3\"")
        } else if (jsProjects.contains(project)) {
            traits.add("fillcolor=\"#ffffba\"")
        } else if (androidProjects.contains(project)) {
            traits.add("fillcolor=\"#baffc9\"")
        } else if (javaProjects.contains(project)) {
            traits.add("fillcolor=\"#ffb3ba\"")
        } else {
            traits.add("fillcolor=\"#eeeeee\"")
        }

        dotWriter.write("  \"${project.path}\" [${traits.joinToString(", ")}];\n")
    }

    dotWriter.write("\n  {rank = same;")
    for (project in projects) {
        if (rootProjects.contains(project)) {
            dotWriter.write(" \"${project.path}\";")
        }
    }
    dotWriter.write("}\n")

    dotWriter.write("\n  # Dependencies\n\n")
    dependencies.forEach { (key, traits) ->
        dotWriter.write("  \"${key.first.path}\" -> \"${key.second.path}\"")
        if (traits.isNotEmpty()) {
            dotWriter.write(" [${traits.joinToString(", ")}]")
        }
        dotWriter.write("\n")
    }

    dotWriter.write("}\n")

    dotWriter.apply {
        flush()
        close()
    }

    println("Project module dependency graph created at ${dotFile.absolutePath}")

    return dotFile
}

fun provideDefaultGraphFile(resetIfExists: Boolean) = provideGraphFile(
    relativePath ="graphs/dependency-graph/project.dot",
    resetIfExists
)

fun provideGraphFile(relativePath: String, resetIfExists: Boolean) =
    File(rootProject.rootDir, relativePath).apply {
        when {
            !exists() -> create()
            exists() && resetIfExists -> {
                delete()
                create()
            }
        }
    }

fun File.create() {
    parentFile.mkdirs()
    createNewFile()
}

fun String.runCommand(workingDir: File = file("/")): String {
    val parts = this.split("\\s".toRegex())
    val proc = ProcessBuilder(*parts.toTypedArray())
        .directory(workingDir)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .start()

    proc.waitFor(1, TimeUnit.MINUTES)
    return proc.inputStream.bufferedReader().readText().trim()
}
