package top.hetaoawa.astra.runtime;

import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.pf4j.*;

/**
 * Only packaged JARs are accepted; public API and runtime bridge classes always come from the host.
 */
public final class AstraPluginManager extends DefaultPluginManager {
  public AstraPluginManager(Path root) {
    super(root);
    var version = new Properties();
    try (var input = AstraPluginManager.class.getResourceAsStream("/astra-version.properties")) {
      if (input == null) throw new IllegalStateException("Missing framework version resource");
      version.load(input);
      setSystemVersion(version.getProperty("framework.version"));
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Cannot read framework version", e);
    }
  }

  @Override
  protected PluginLoader createPluginLoader() {
    return new JarPluginLoader(this) {
      @Override
      protected PluginClassLoader createPluginClassLoader(Path path, PluginDescriptor descriptor) {
        return new PluginClassLoader(
            pluginManager, descriptor, AstraPluginManager.class.getClassLoader()) {
          @Override
          protected boolean shouldDelegateToParent(String name) {
            return name.startsWith("top.hetaoawa.astra.api.")
                || name.startsWith("top.hetaoawa.astra.runtime.")
                || name.startsWith("org.pf4j.")
                || name.startsWith("org.slf4j.")
                || super.shouldDelegateToParent(name);
          }
        };
      }
    };
  }

  @Override
  protected PluginDescriptorFinder createPluginDescriptorFinder() {
    return new ManifestPluginDescriptorFinder();
  }

  @Override
  protected void validatePluginDescriptor(PluginDescriptor descriptor) {
    super.validatePluginDescriptor(descriptor);
    if (!descriptor.getPluginId().matches("[a-zA-Z0-9_.-]{1,128}"))
      throw new IllegalArgumentException("Invalid plugin ID");
    if (!descriptor.getPluginClass().equals(BridgePlugin.class.getName()))
      throw new IllegalArgumentException("Plugin-Class must be the Astra host bridge");
  }

  public static final class BridgePlugin extends Plugin {
    private top.hetaoawa.astra.api.AstraPlugin delegate;

    public BridgePlugin(PluginWrapper wrapper) {
      super(wrapper);
      try (var jar = new JarFile(wrapper.getPluginPath().toFile())) {
        String entry = jar.getManifest().getMainAttributes().getValue("Astra-Entrypoint");
        String api = jar.getManifest().getMainAttributes().getValue("Astra-Api-Version");
        if (!"1".equals(api) || entry == null || entry.isBlank())
          throw new IllegalArgumentException("Astra API version 1 and entrypoint required");
        delegate =
            (top.hetaoawa.astra.api.AstraPlugin)
                wrapper.getPluginClassLoader().loadClass(entry).getConstructor().newInstance();
      } catch (Exception e) {
        throw new IllegalArgumentException(
            "Cannot instantiate Astra plugin " + wrapper.getPluginId(), e);
      }
    }

    public top.hetaoawa.astra.api.AstraPlugin delegate() {
      return delegate;
    }
  }
}
