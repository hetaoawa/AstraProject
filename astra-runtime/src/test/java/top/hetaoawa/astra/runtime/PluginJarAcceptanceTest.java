package top.hetaoawa.astra.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.hetaoawa.astra.api.*;
import top.hetaoawa.astra.core.*;
import top.hetaoawa.astra.qq.*;
import top.hetaoawa.astra.storage.*;

class PluginJarAcceptanceTest {
  @TempDir Path temp;

  @Test
  void independentJarsShareOnlyHostApiAndDisableRemovesOwnedResources() throws Exception {
    Path pluginRoot = Files.createDirectory(temp.resolve("plugins"));
    jar(pluginRoot, "one", "first");
    jar(pluginRoot, "two", "second");
    var bot = new BotId("app", "production");
    var policies = List.of(new BotPolicy(bot, 2, 10, "", false));
    var registry = new HandlerRegistry();
    var services = new ServiceRegistry();
    var manager = new AstraPluginManager(pluginRoot);
    try (var db = new JdbcDatabase("sqlite", "jdbc:sqlite:" + temp.resolve("plugins.db"), "", "");
        var sessions = new SessionManager();
        var tasks = new TaskManager();
        var outbound =
            new OutboundQueue(
                new MemoryEventQueue(policies),
                new JdbcOutboundStore(db),
                Map.of(
                    bot,
                    new DirectQqApi(
                        new BotCredentials(
                            bot, "secret", URI.create("http://127.0.0.1:1"), Duration.ofSeconds(1)),
                        new RouteCatalog())),
                Map.of(bot, 5d),
                (job, result) -> {})) {
      var directory = new JdbcDirectory(db, policies);
      var history = new JdbcHistory(db, false);
      ContextFactory contexts =
          (id, b) ->
              new RuntimeContext(
                  id,
                  b,
                  registry,
                  outbound,
                  new JdbcOutboundStore(db),
                  directory,
                  history,
                  sessions,
                  tasks,
                  services,
                  Map.of());
      try (var host =
          new PluginHost(
              manager,
              registry,
              contexts,
              List.of(bot),
              sessions,
              tasks,
              outbound,
              db,
              Map.of(bot, services),
              true)) {
        assertEquals(2, host.list().size());
        assertEquals(2, tasks.count());
        assertEquals(2, services.permissions().size());
        ClassLoader first = manager.getPluginClassLoader("one"),
            second = manager.getPluginClassLoader("two");
        assertNotSame(first.loadClass("fixture.Library"), second.loadClass("fixture.Library"));
        assertSame(AstraPlugin.class, first.loadClass(AstraPlugin.class.getName()));
        assertEquals("first", first.loadClass("fixture.Library").getMethod("value").invoke(null));
        assertEquals("second", second.loadClass("fixture.Library").getMethod("value").invoke(null));
        host.setEnabled("one", bot, false);
        assertEquals(1, tasks.count());
        assertEquals(1, services.permissions().size());
        assertFalse(registry.enabled("one", bot));
        assertTrue(registry.enabled("two", bot));
        host.setEnabled("one", bot, true);
        assertEquals(2, tasks.count());
        assertEquals(2, services.permissions().size());
        host.setEnabled("two", null, false);
        host.setEnabled("one", null, false);
        assertEquals(0, tasks.count());
        assertTrue(services.permissions().isEmpty());
      }
      assertTrue(manager.getPlugins().isEmpty());
    }
  }

  private void jar(Path root, String id, String value) throws Exception {
    Path work = Files.createDirectory(temp.resolve(id));
    Path source = Files.createDirectories(work.resolve("fixture"));
    Files.writeString(
        source.resolve("Library.java"),
        "package fixture;public class Library{public static String value(){return \""
            + value
            + "\";}}");
    Files.writeString(
        source.resolve("Entry.java"),
        "package fixture;import top.hetaoawa.astra.api.*;import"
            + " top.hetaoawa.astra.api.annotation.*;public class Entry implements"
            + " AstraPlugin{public void onEnable(PluginContext"
            + " c){c.services().registerPermission(new"
            + " PermissionNode(c.pluginId()+\".use\",Library.value(),c.pluginId()));c.tasks().interval(java.time.Duration.ofHours(1),java.time.Duration.ofHours(1),()->{});}@OnMessage"
            + " public void handle(MessageEvent e){}}");
    String api =
        Path.of(AstraPlugin.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            .toString();
    assertEquals(
        0,
        ToolProvider.getSystemJavaCompiler()
            .run(
                null,
                null,
                null,
                "-classpath",
                api,
                "-d",
                work.toString(),
                source.resolve("Library.java").toString(),
                source.resolve("Entry.java").toString()));
    Manifest manifest = new Manifest();
    var a = manifest.getMainAttributes();
    a.putValue("Manifest-Version", "1.0");
    a.putValue("Plugin-Id", id);
    a.putValue("Plugin-Version", "1.0.0");
    a.putValue("Plugin-Requires", ">=0.1.0");
    a.putValue("Plugin-Class", AstraPluginManager.BridgePlugin.class.getName());
    a.putValue("Astra-Entrypoint", "fixture.Entry");
    a.putValue("Astra-Api-Version", "1");
    try (var jar =
        new JarOutputStream(Files.newOutputStream(root.resolve(id + ".jar")), manifest)) {
      for (String name : List.of("Entry", "Library")) {
        jar.putNextEntry(new JarEntry("fixture/" + name + ".class"));
        jar.write(Files.readAllBytes(source.resolve(name + ".class")));
        jar.closeEntry();
      }
    }
  }
}
