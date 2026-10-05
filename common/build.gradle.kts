plugins {
    id("multiloader-base")
    id("java-library")

    id("net.fabricmc.fabric-loom") version ("1.17.20")
}

base {
    archivesName = "sodium-common"
}

// ---------------------------------------------------------------------------
// Vertex position bit-width profile (build-time toggle)
//
//   ./gradlew build                          -> uses vertex.bits from gradle.properties
//   ./gradlew build -Pvertex.bits=ideal      -> 9/9/9 (vanilla-parity precision)
//   ./gradlew build -Pvertex.bits=original   -> 5/9/5 (one stored step per block on X/Z)
//
// This property is the single source of truth: it is expanded into the generated
// VertexBits java class *and* into chunk_vertex.glsl, so the encoder and the shader
// can never disagree about the layout.
// ---------------------------------------------------------------------------
val vertexBitsProfile: String = (project.findProperty("vertex.bits") as String?) ?: "original"

// Bit layout per profile: x | y | z
val vertexBitLayouts = mapOf(
    "original" to Triple(5, 9, 5),
    "ideal" to Triple(9, 9, 9),
)
val vertexBits = vertexBitLayouts[vertexBitsProfile]
    ?: throw GradleException(
        "Unknown vertex.bits '$vertexBitsProfile'. Expected one of: ${vertexBitLayouts.keys.joinToString()}"
    )

val generatedSourcesDir = layout.buildDirectory.dir("generated/sources/vertexBits/java").get().asFile

val generateVertexBits = tasks.register("generateVertexBits") {
    val (bx, by, bz) = vertexBits
    val outputDir = generatedSourcesDir
    val pkgDir = File(outputDir, "net/caffeinemc/mods/sodium/client/render/chunk/vertex/format")

    inputs.property("profile", vertexBitsProfile)
    inputs.property("bits", "$bx/$by/$bz")
    outputs.dir(outputDir)

    doLast {
        pkgDir.mkdirs()
        File(pkgDir, "VertexBits.java").writeText(
            """
            package net.caffeinemc.mods.sodium.client.render.chunk.vertex.format;

            /**
             * GENERATED FILE - do not edit by hand.
             * Produced by the Gradle task ':common:generateVertexBits' from the 'vertex.bits' property.
             * Changing the value in gradle.properties (or -Pvertex.bits=...) regenerates this file.
             */
            public final class VertexBits {
                public static final String PROFILE = "$vertexBitsProfile";

                public static final int X_BITS = $bx;
                public static final int Y_BITS = $by;
                public static final int Z_BITS = $bz;

                /** Total packed position width; must be <= 32. */
                public static final int TOTAL_BITS = X_BITS + Y_BITS + Z_BITS;

                public static final int X_SHIFT = 0;
                public static final int Y_SHIFT = X_SHIFT + X_BITS;
                public static final int Z_SHIFT = Y_SHIFT + Y_BITS;

                public static final int X_MAX = (1 << X_BITS) - 1;
                public static final int Y_MAX = (1 << Y_BITS) - 1;
                public static final int Z_MAX = (1 << Z_BITS) - 1;

                /** Model-space window covered by the packed position, in blocks. */
                public static final float MODEL_ORIGIN = 8.0f;
                public static final float MODEL_RANGE = 32.0f;

                private VertexBits() {
                }
            }
            """.trimIndent()
        )
    }
}

sourceSets {
    named("main") {
        java.srcDir(generatedSourcesDir)
    }
}

tasks.named<JavaCompile>("compileJava") {
    dependsOn(generateVertexBits)
}

val configurationPreLaunch = configurations.create("preLaunchDeps") {
    isCanBeResolved = true
}

sourceSets {
    val main = getByName("main")
    val api = create("api")
    val boot = create("boot")

    api.apply {
        java {
            compileClasspath += main.compileClasspath
        }
    }

    boot.apply {
        java {
            compileClasspath += configurationPreLaunch
        }
    }

    main.apply {
        java {
            compileClasspath += api.output
            compileClasspath += boot.output
        }
    }

    create("desktop")
}

repositories {
    mavenLocal()
}

dependencies {
    minecraft("com.mojang:minecraft:${BuildConfig.MINECRAFT_VERSION}")

    compileOnly("io.github.llamalad7:mixinextras-common:0.5.0")
    annotationProcessor("io.github.llamalad7:mixinextras-common:0.5.0")

    compileOnly("net.fabricmc:sponge-mixin:0.13.2+mixin.0.8.5")
    compileOnly("net.fabricmc:fabric-loader:${BuildConfig.FABRIC_LOADER_VERSION}")

    // We need to be careful during pre-launch that we don't touch any Minecraft classes, since other mods
    // will not yet have an opportunity to apply transformations.
    configurationPreLaunch("org.lwjgl:lwjgl:3.4.3")
    configurationPreLaunch("org.lwjgl:lwjgl-opengl:3.4.3")
    configurationPreLaunch("org.lwjgl:lwjgl-sdl:3.4.3")
    configurationPreLaunch("net.java.dev.jna:jna:5.14.0")
    configurationPreLaunch("net.java.dev.jna:jna-platform:5.14.0")
    configurationPreLaunch("org.slf4j:slf4j-api:2.0.9")
    configurationPreLaunch("org.jspecify:jspecify:1.0.0")
}

loom {
    accessWidenerPath = file("src/main/resources/sodium-common.accesswidener")

    mixin {
        useLegacyMixinAp = false
    }
}

fun exportSourceSetJava(name: String, sourceSet: SourceSet) {
    val configuration = configurations.create("${name}Java") {
        isCanBeResolved = true
        isCanBeConsumed = true
    }

    val compileTask = tasks.getByName<JavaCompile>(sourceSet.compileJavaTaskName)
    artifacts.add(configuration.name, compileTask.destinationDirectory) {
        builtBy(compileTask)
    }
}

fun exportSourceSetSources(name: String, sourceSet: SourceSet) {
    val configuration = configurations.create("${name}Sources") {
        isCanBeResolved = true
        isCanBeConsumed = true
    }

    val compileTask = tasks.register<Copy>(sourceSet.getTaskName("process", "sources")) {
        from(sourceSet.allSource)
        into(file(project.layout.buildDirectory).resolve("sources").resolve(sourceSet.name))
    }.get()
    artifacts.add(configuration.name, compileTask.destinationDir) {
        builtBy(compileTask)
    }
}

fun exportSourceSetResources(name: String, sourceSet: SourceSet) {
    val configuration = configurations.create("${name}Resources") {
        isCanBeResolved = true
        isCanBeConsumed = true
    }

    val compileTask = tasks.getByName<ProcessResources>(sourceSet.processResourcesTaskName)
    compileTask.apply {
        exclude("**/README.txt")
        exclude("/*.accesswidener")
    }

    artifacts.add(configuration.name, compileTask.destinationDir) {
        builtBy(compileTask)
    }
}

// Exports the compiled output of the source set to the named configuration.
fun exportSourceSet(name: String, sourceSet: SourceSet) {
    exportSourceSetJava(name, sourceSet)
    exportSourceSetSources(name, sourceSet)
    exportSourceSetResources(name, sourceSet)
}

exportSourceSet("commonMain", sourceSets["main"])
exportSourceSet("commonApi", sourceSets["api"])
exportSourceSet("commonBoot", sourceSets["boot"])
exportSourceSet("commonDesktop", sourceSets["desktop"])

tasks.jar { enabled = false }

// Keep the chunk vertex shader's packed-position layout in lock-step with the generated
// VertexBits java class, so the encoder and the decoder can never drift apart.
tasks.named<ProcessResources>("processResources") {
    val (bx, by, bz) = vertexBits

    filesMatching("**/chunk_vertex.glsl") {
        expand(
            "positionXBits" to bx,
            "positionYBits" to by,
            "positionZBits" to bz,
            "positionXMax" to ((1 shl bx) - 1),
            "positionYMax" to ((1 shl by) - 1),
            "positionZMax" to ((1 shl bz) - 1),
        )
    }
}