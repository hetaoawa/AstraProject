// SPDX-License-Identifier: EPL-2.0
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.JarFile;
import java.util.zip.*;
import javax.xml.parsers.DocumentBuilderFactory;

/** Java 21 source launcher shared by the source checkout and generated plugin projects. */
class AstraDev {
  static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");
  static final Scanner INPUT = new Scanner(System.in);
  static final String REPOSITORY = "hetaoawa/AstraProject";
  static Map<String, String> options = new LinkedHashMap<>();

  public static void main(String[] args) throws Exception {
    if (Runtime.version().feature() < 21) throw new IllegalStateException("Java 21 JDK is required.");
    if (args.length == 0) throw new IllegalArgumentException("Commands: new, init, setup, package, dev, release");
    for (int i = 1; i < args.length; i += 2) {
      if (!args[i].startsWith("--") || i + 1 == args.length)
        throw new IllegalArgumentException("Expected --option value: " + args[i]);
      String key = args[i].substring(2);
      require(Set.of("source", "template", "manifest", "directory", "repository", "id", "name", "package",
          "group", "plugin-version", "assets", "project", "build-root", "output").contains(key), "Unknown option: " + args[i]);
      options.put(key, args[i + 1]);
    }
    switch (args[0]) {
      case "new", "init" -> initialize(args[0].equals("new"));
      case "setup", "package", "dev" -> develop(args[0]);
      case "release" -> release();
      default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
    }
  }

  static String option(String key, String fallback) { return options.getOrDefault(key, fallback); }
  static Path absolute(String value) { return Path.of(value).toAbsolutePath().normalize(); }
  static String ask(String key, String label, String fallback) {
    if (options.containsKey(key)) return options.get(key);
    System.out.print(label + " [" + fallback + "]: ");
    String value = INPUT.hasNextLine() ? INPUT.nextLine().strip() : "";
    return value.isEmpty() ? fallback : value;
  }
  static void require(boolean condition, String message) {
    if (!condition) throw new IllegalArgumentException(message);
  }
  static Properties read(Path path) throws IOException {
    Properties p = new Properties();
    try (var reader = Files.newBufferedReader(path)) { p.load(reader); }
    return p;
  }
  static void writeProperties(Path path, Properties p) throws IOException {
    Files.createDirectories(path.getParent());
    try (var writer = Files.newBufferedWriter(path)) { p.store(writer, "Astra development configuration"); }
  }
  static void text(Path path, String content) throws IOException {
    Files.createDirectories(path.getParent());
    Files.writeString(path, content, StandardCharsets.UTF_8);
    if (path.toString().endsWith(".sh") || path.getFileName().toString().equals("mvnw"))
      path.toFile().setExecutable(true, false);
  }
  static String xml(String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;");
  }
  static String projectVersion(Path root) throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    var doc = factory.newDocumentBuilder().parse(root.resolve("pom.xml").toFile());
    var nodes = doc.getDocumentElement().getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++)
      if (nodes.item(i).getNodeName().equals("version")) return nodes.item(i).getTextContent();
    throw new IllegalArgumentException("Missing project version");
  }
  static Path cache(Path project) throws IOException {
    String configured = System.getenv("ASTRA_CACHE_ROOT");
    Path path = configured == null || configured.isBlank()
        ? project.resolve(".astra/cache") : absolute(configured);
    Files.createDirectories(path);
    return path;
  }
  static void copyTree(Path from, Path to, Map<String, String> replacements) throws Exception {
    try (var stream = Files.walk(from)) {
      for (Path file : stream.filter(Files::isRegularFile).toList()) {
        String relative = from.relativize(file).toString();
        if (Set.of("README.md", "LICENSE", "NOTICE").contains(relative)
            || relative.startsWith("tooling" + File.separator)) continue;
        if (relative.startsWith("src" + File.separator))
          relative = relative.replace("example" + File.separator + "MyPlugin.java",
              replacements.get("__PACKAGE_PATH__") + File.separator + "MyPlugin.java");
        String content = Files.readString(file);
        for (var entry : replacements.entrySet()) content = content.replace(entry.getKey(), entry.getValue());
        text(to.resolve(relative), content);
      }
    }
  }

  static void initialize(boolean sourceMode) throws Exception {
    Path source = sourceMode ? absolute(option("source", ".")) : null;
    Path template = absolute(option("template", sourceMode ? source.resolve("templates/plugin").toString() : "."));
    Properties manifest = sourceMode ? new Properties() : read(absolute(option("manifest", "astra-release.properties")));
    String version = sourceMode ? projectVersion(source) : manifest.getProperty("framework.version");
    require(version != null && version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?"), "Invalid framework version");
    if (!sourceMode) {
      require("1".equals(manifest.getProperty("schema.version")), "Unsupported release manifest schema");
      String apiVersion = manifest.getProperty("api.version", "");
      require(apiVersion.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?"), "Invalid API version");
    }
    String id = ask("id", "Plugin ID", "my-plugin");
    String name = ask("name", "Plugin display name", id);
    String group = ask("group", "Maven group ID", "example");
    String pkg = ask("package", "Java package", group + "." + id.replace('-', '_'));
    String pluginVersion = ask("plugin-version", "Plugin version", "0.1.0");
    require(id.matches("[a-z][a-z0-9-]{0,63}"), "Plugin ID must match [a-z][a-z0-9-]{0,63}");
    String javaPackage = "[a-zA-Z_$][a-zA-Z0-9_$]*(?:\\.[a-zA-Z_$][a-zA-Z0-9_$]*)*";
    require(group.matches(javaPackage) && pkg.matches(javaPackage), "Invalid group ID or Java package");
    Set<String> keywords = Set.of("abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null", "_");
    require(Arrays.stream(pkg.split("\\.")).noneMatch(keywords::contains), "Java package contains a reserved word");
    require(pluginVersion.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?"), "Invalid plugin version");
    Path target = absolute(option("directory", sourceMode ? source.resolve("plugins-src/" + id).toString() : "."));
    if (Files.exists(target)) {
      try (var files = Files.list(target)) {
        require(files.allMatch(p -> Set.of(".git", "init.ps1", "init.sh").contains(p.getFileName().toString())),
            "Target must be empty (an existing .git and initializer are allowed): " + target);
      }
    }
    Files.createDirectories(target);
    var replacements = new LinkedHashMap<String, String>();
    replacements.put("__PLUGIN_ID__", id);
    replacements.put("__PLUGIN_NAME__", xml(name));
    replacements.put("__GROUP_ID__", group);
    replacements.put("__PACKAGE__", pkg);
    replacements.put("__PACKAGE_PATH__", pkg.replace('.', File.separatorChar));
    replacements.put("__PLUGIN_VERSION__", pluginVersion);
    replacements.put("__FRAMEWORK_VERSION__", version);
    replacements.put("__API_VERSION__", sourceMode ? version : manifest.getProperty("api.version"));
    copyTree(template, target, replacements);
    Path tool = sourceMode ? source.resolve("scripts/lib/AstraDev.java") : template.resolve("tooling/AstraDev.java");
    Files.createDirectories(target.resolve(".astra-tools"));
    Files.copy(tool, target.resolve(".astra-tools/AstraDev.java"));
    copyLegal(sourceMode ? source : template, target.resolve(".astra-tools"));
    Properties project = new Properties();
    project.setProperty("framework.version", version);
    project.setProperty("api.version", replacements.get("__API_VERSION__"));
    project.setProperty("plugin.id", id);
    project.setProperty("repository", option("repository", REPOSITORY));
    writeProperties(target.resolve("astra-project.properties"), project);
    if (sourceMode) {
      Properties local = new Properties();
      local.setProperty("source.root", source.toString());
      writeProperties(target.resolve(".astra/source.properties"), local);
    } else {
      Files.createDirectories(target.resolve(".astra"));
      Files.copy(absolute(option("manifest", "astra-release.properties")), target.resolve(".astra/release.properties"));
      if (options.containsKey("assets")) {
        Properties local = new Properties();
        local.setProperty("assets.directory", absolute(options.get("assets")).toString());
        writeProperties(target.resolve(".astra/assets.properties"), local);
      }
      try {
        setup(target, project);
      } catch (Exception failure) {
        System.err.println("Project files are ready at " + target
            + ". After fixing the download problem, retry scripts/plugin.ps1 setup or sh scripts/plugin.sh setup.");
        throw failure;
      }
    }
    text(target.resolve("README.md"), "# " + name.replace('\n', ' ').replace('\r', ' ') + "\n\nAstra " + version
        + " plugin. Requires Java 21 JDK.\n\n"
        + "Windows: `./scripts/plugin.ps1 package`, `./scripts/plugin.ps1 dev`.\n\n"
        + "Linux/macOS: `sh scripts/plugin.sh package`, `sh scripts/plugin.sh dev`.\n\n"
        + "After cloning: run `setup` to download the pinned runtime, then edit `.astra/runtime/config/application.yml`.\n"
        + "Set QQ_APP_ID and QQ_BOT_SECRET before connecting a bot. Local runtime/configuration/data are ignored by Git.\n\n"
        + "Publish `dist/" + id + "-" + pluginVersion + ".jar`. Runtime dependency libraries are shaded; Astra API is provided by the host.\n");
    Files.writeString(target.resolve("README.md"),
        "\nThe bundled Astra development tools and example code are under EPL-2.0. See `.astra-tools/LICENSE` and `.astra-tools/NOTICE`. Choose appropriate licensing for your own original plugin code.\n",
        StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    System.out.println("Created " + target + " (Astra " + version + "). Run scripts/plugin.ps1 dev or sh scripts/plugin.sh dev.");
  }

  static void develop(String command) throws Exception {
    Path project = absolute(option("project", "."));
    Properties config = read(project.resolve("astra-project.properties"));
    if (command.equals("setup") || command.equals("dev")) setup(project, config);
    else if (Files.exists(project.resolve(".astra/source.properties"))) sourceBuild(project, config);
    if (command.equals("setup")) return;
    Path work = cache(project).resolve("build/" + config.getProperty("plugin.id"));
    runMaven(project, List.of("-Dastra.buildRoot=" + work, "package"));
    String finalName = projectVersion(project).strip();
    Path jar = work.resolve(config.getProperty("plugin.id") + "-" + finalName + ".jar");
    require(Files.isRegularFile(jar), "Plugin artifact not found: " + jar);
    Files.createDirectories(project.resolve("dist"));
    Files.copy(jar, project.resolve("dist/" + jar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
    System.out.println("Plugin: " + project.resolve("dist/" + jar.getFileName()));
    if (command.equals("dev")) {
      Path runtime = project.resolve(".astra/runtime");
      Path installed = runtime.resolve("plugins/" + config.getProperty("plugin.id") + ".jar");
      try (var files = Files.list(runtime.resolve("plugins"))) {
        for (Path existing : files.filter(p -> p.toString().endsWith(".jar")).toList()) {
          try (var loaded = new JarFile(existing.toFile())) {
            String id = loaded.getManifest().getMainAttributes().getValue("Plugin-Id");
            require(!config.getProperty("plugin.id").equals(id) || existing.equals(installed),
                "Duplicate plugin ID in runtime; remove the older JAR yourself: " + existing);
          }
        }
      }
      Files.copy(jar, installed, StandardCopyOption.REPLACE_EXISTING);
      run(runtime, List.of(javaExecutable(), "-jar", "astra.jar"));
    }
  }
  static String javaExecutable() {
    return Path.of(System.getProperty("java.home"), "bin", WINDOWS ? "java.exe" : "java").toString();
  }
  static void run(Path directory, List<String> command) throws Exception {
    var builder = new ProcessBuilder(command).directory(directory.toFile()).inheritIO();
    builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
    int status = builder.start().waitFor();
    if (status != 0) throw new IOException("Command failed (" + status + "): " + command.getFirst());
  }
  static void runMaven(Path directory, List<String> goals) throws Exception {
    List<String> command = new ArrayList<>();
    if (WINDOWS) command.addAll(List.of("cmd.exe", "/d", "/c", "mvnw.cmd"));
    else command.addAll(List.of("sh", "./mvnw"));
    command.add("--batch-mode");
    for (String[] mapping : List.of(new String[]{"ASTRA_MAVEN_REPOSITORY", "-Dmaven.repo.local="},
        new String[]{"ASTRA_MAVEN_SETTINGS", "--settings="})) {
      String value = System.getenv(mapping[0]);
      if (value != null && !value.isBlank()) command.add(mapping[1] + absolute(value));
    }
    command.addAll(goals);
    run(directory, command);
  }
  static void sourceBuild(Path project, Properties config) throws Exception {
    Path source = absolute(read(project.resolve(".astra/source.properties")).getProperty("source.root"));
    require(projectVersion(source).equals(config.getProperty("framework.version")),
        "Source framework version changed; create a matching plugin project or explicitly update its version configuration.");
    Path build = cache(project).resolve("framework-build");
    runMaven(source, List.of("-Dastra.buildRoot=" + build, "-pl", "astra-runtime", "-am", "install", "-DskipTests"));
    Path runtime = project.resolve(".astra/runtime");
    prepareRuntime(runtime, source.resolve("distribution/runtime"), source.resolve("config/application.example.yml"));
    Files.copy(build.resolve("astra-runtime/astra-runtime-" + config.getProperty("framework.version") + ".jar"),
        runtime.resolve("astra.jar"), StandardCopyOption.REPLACE_EXISTING);
    copyLegal(source, runtime);
  }
  static void prepareRuntime(Path runtime, Path scripts, Path example) throws Exception {
    Files.createDirectories(runtime.resolve("plugins"));
    Files.createDirectories(runtime.resolve("config"));
    for (String name : List.of("start.ps1", "start.sh")) {
      Path destination = runtime.resolve(name);
      if (!Files.exists(destination)) text(destination, Files.readString(scripts.resolve(name)));
    }
    Path destination = runtime.resolve("config/application.yml");
    if (!Files.exists(destination)) Files.copy(example, destination);
  }
  static void setup(Path project, Properties config) throws Exception {
    if (Files.exists(project.resolve(".astra/source.properties"))) { sourceBuild(project, config); return; }
    String version = config.getProperty("framework.version");
    Path runtime = project.resolve(".astra/runtime");
    Path marker = runtime.resolve(".astra-version");
    if (Files.isRegularFile(marker)) {
      require(Files.readString(marker).strip().equals(version), "Runtime version differs; keep data/config before changing runtime directories.");
      if (Files.isRegularFile(runtime.resolve("astra.jar"))) return;
    }
    String base = "https://github.com/" + config.getProperty("repository") + "/releases/download/framework-v" + version + "/";
    Path downloads = cache(project).resolve("downloads/" + version);
    Files.createDirectories(downloads);
    Path assets = null;
    if (Files.exists(project.resolve(".astra/assets.properties")))
      assets = absolute(read(project.resolve(".astra/assets.properties")).getProperty("assets.directory"));
    Path manifestPath = project.resolve(".astra/release.properties");
    if (!Files.exists(manifestPath)) fetch(base, "astra-release.properties", manifestPath, null, assets);
    Properties manifest = read(manifestPath);
    require("1".equals(manifest.getProperty("schema.version")), "Unsupported release manifest schema");
    require(version.equals(manifest.getProperty("framework.version")), "Release manifest framework version mismatch");
    require(config.getProperty("api.version").equals(manifest.getProperty("api.version")), "Release manifest API version mismatch");
    String file = manifest.getProperty("runtime.file");
    require(file != null && file.matches("[A-Za-z0-9._-]+\\.zip"), "Invalid runtime filename");
    Path zip = downloads.resolve(file);
    String runtimeHash = manifest.getProperty("runtime.sha256", "");
    require(runtimeHash.matches("[a-fA-F0-9]{64}"), "Missing or invalid runtime SHA-256");
    fetch(base, file, zip, runtimeHash, assets);
    Path staged = downloads.resolve("runtime-" + UUID.randomUUID());
    unzip(zip, staged);
    require(Files.isRegularFile(staged.resolve("astra.jar")), "Invalid runtime archive");
    Files.createDirectories(runtime);
    try (var files = Files.walk(staged)) {
      for (Path entry : files.filter(Files::isRegularFile).toList()) {
        Path dest = runtime.resolve(staged.relativize(entry));
        Files.createDirectories(dest.getParent());
        // Never replace configuration, plugins, or existing data while setting up a project.
        if (!Files.exists(dest) || dest.equals(runtime.resolve("astra.jar")))
          Files.copy(entry, dest, StandardCopyOption.REPLACE_EXISTING);
      }
    }
    Path example = runtime.resolve("config/application.example.yml");
    if (!Files.exists(runtime.resolve("config/application.yml")))
      Files.copy(example, runtime.resolve("config/application.yml"));
    text(marker, version + "\n");
    System.out.println("Runtime prepared: " + runtime + "; edit config/application.yml before connecting QQ.");
  }
  static String hash(Path file) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    try (var input = Files.newInputStream(file)) {
      byte[] buffer = new byte[65536];
      int count;
      while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
    }
    return HexFormat.of().formatHex(digest.digest());
  }
  static void fetch(String base, String name, Path target, String expected, Path assets) throws Exception {
    if (expected != null) require(expected.matches("[a-fA-F0-9]{64}"), "Invalid SHA-256 for " + name);
    if (expected != null && Files.isRegularFile(target) && hash(target).equalsIgnoreCase(expected)) return;
    Files.createDirectories(target.getParent());
    Path partial = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".part");
    if (assets != null) Files.copy(assets.resolve(name), partial);
    else {
      URLConnection connection = URI.create(base + name).toURL().openConnection();
      connection.setConnectTimeout(30000); connection.setReadTimeout(120000);
      connection.setRequestProperty("User-Agent", "Astra-Devkit");
      try (var input = connection.getInputStream()) { Files.copy(input, partial); }
    }
    require(expected == null || hash(partial).equalsIgnoreCase(expected), "SHA-256 mismatch: " + name);
    Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
  }
  static void unzip(Path zip, Path destination) throws Exception {
    Files.createDirectories(destination);
    try (var input = new ZipInputStream(Files.newInputStream(zip))) {
      ZipEntry entry;
      while ((entry = input.getNextEntry()) != null) {
        Path path = destination.resolve(entry.getName()).normalize();
        require(path.startsWith(destination), "Unsafe archive path");
        if (entry.isDirectory()) Files.createDirectories(path);
        else { Files.createDirectories(path.getParent()); Files.copy(input, path); }
      }
    }
  }
  static void zip(Path source, Path output) throws Exception {
    try (var zip = new ZipOutputStream(Files.newOutputStream(output)); var files = Files.walk(source)) {
      for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
        var entry = new ZipEntry(source.relativize(file).toString().replace('\\', '/'));
        entry.setTime(0);
        zip.putNextEntry(entry); Files.copy(file, zip); zip.closeEntry();
      }
    }
  }
  static void copyAll(Path source, Path destination) throws Exception {
    try (var files = Files.walk(source)) {
      for (Path path : files.filter(Files::isRegularFile).toList()) {
        Path dest = destination.resolve(source.relativize(path));
        Files.createDirectories(dest.getParent());
        Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }
  static void copyLegal(Path source, Path destination) throws IOException {
    Files.createDirectories(destination);
    for (String file : List.of("LICENSE", "NOTICE"))
      Files.copy(source.resolve(file), destination.resolve(file), StandardCopyOption.REPLACE_EXISTING);
  }
  static void release() throws Exception {
    Path source = absolute(option("source", "."));
    String version = projectVersion(source);
    Path build = absolute(option("build-root", source.resolve("target").toString()));
    Path output = absolute(option("output", source.resolve("release").toString()));
    Path work = cache(source).resolve("release/" + UUID.randomUUID());
    Files.createDirectories(output);
    Path runtime = work.resolve("runtime"), template = work.resolve("template");
    copyAll(source.resolve("distribution/runtime"), runtime);
    copyLegal(source, runtime);
    text(runtime.resolve("version.txt"), version + "\n");
    Files.createDirectories(runtime.resolve("config"));
    Files.createDirectories(runtime.resolve("plugins"));
    Files.copy(source.resolve("config/application.example.yml"), runtime.resolve("config/application.example.yml"));
    text(runtime.resolve("plugins/README.txt"), "Place Astra plugin JARs here and restart the application.\n");
    for (String artifact : List.of("astra-runtime", "astra-api", "astra-example-plugin")) {
      Path jar = build.resolve(artifact + "/" + artifact + "-" + version + ".jar");
      require(Files.isRegularFile(jar), "Build first: " + jar);
      Files.copy(jar, output.resolve(jar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
      if (artifact.equals("astra-runtime")) Files.copy(jar, runtime.resolve("astra.jar"));
      if (artifact.equals("astra-example-plugin")) {
        Files.createDirectories(runtime.resolve("examples"));
        Files.copy(jar, runtime.resolve("examples/" + jar.getFileName()));
      }
    }
    String runtimeFile = "astra-runtime-" + version + ".zip";
    zip(runtime, output.resolve(runtimeFile));
    Properties templateConfig = read(source.resolve("templates/template.properties"));
    String templateVersion = templateConfig.getProperty("template.version");
    copyAll(source.resolve("templates/plugin"), template);
    copyLegal(source, template);
    Files.createDirectories(template.resolve("tooling"));
    Files.copy(source.resolve("scripts/lib/AstraDev.java"), template.resolve("tooling/AstraDev.java"));
    String templateFile = "astra-plugin-template-" + templateVersion + ".zip";
    zip(template, output.resolve(templateFile));
    String manifest = "schema.version=1\nframework.version=" + version + "\napi.version=" + version
        + "\napi.groupId=top.hetaoawa\napi.artifactId=astra-api\ntemplate.version=" + templateVersion
        + "\ntemplate.file=" + templateFile + "\ntemplate.sha256=" + hash(output.resolve(templateFile))
        + "\nruntime.file=" + runtimeFile + "\nruntime.sha256=" + hash(output.resolve(runtimeFile)) + "\n";
    text(output.resolve("astra-release.properties"), manifest);
    for (String script : List.of("init.ps1", "init.sh"))
      Files.copy(source.resolve("scripts/" + script), output.resolve(script), StandardCopyOption.REPLACE_EXISTING);
    // Use the same standalone POM that Maven installs, signs and deploys.
    Path apiPom = build.resolve("astra-api/astra-api-" + version + ".pom");
    require(Files.isRegularFile(apiPom), "Rebuild the API to generate its standalone POM: " + apiPom);
    Files.copy(apiPom, output.resolve(apiPom.getFileName()), StandardCopyOption.REPLACE_EXISTING);
    List<String> sums = new ArrayList<>();
    for (String classifier : List.of("sources", "javadoc")) {
      String file = "astra-api-" + version + "-" + classifier + ".jar";
      Path built = build.resolve("astra-api/" + file);
      if (Files.isRegularFile(built)) {
        Files.copy(built, output.resolve(file), StandardCopyOption.REPLACE_EXISTING);
        sums.add(hash(output.resolve(file)) + "  " + file);
      }
    }
    List<String> published = List.of("astra-runtime-" + version + ".jar", "astra-api-" + version + ".jar",
        "astra-example-plugin-" + version + ".jar", "astra-api-" + version + ".pom", runtimeFile,
        templateFile, "astra-release.properties", "init.ps1", "init.sh");
    for (String file : published) sums.add(hash(output.resolve(file)) + "  " + file);
    text(output.resolve("SHA256SUMS"), String.join("\n", sums) + "\n");
    System.out.println("Release " + version + ": " + output);
  }
}
