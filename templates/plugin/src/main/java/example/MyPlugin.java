// SPDX-License-Identifier: EPL-2.0
package __PACKAGE__;

import java.util.concurrent.CompletionStage;
import top.hetaoawa.astra.api.*;
import top.hetaoawa.astra.api.annotation.*;

public final class MyPlugin implements AstraPlugin {
  @OnCommand("hello")
  public CompletionStage<ApiResponse> hello(
      MessageEvent event, PluginContext context, CommandArgs args) {
    return context.reply(event, "你好 " + args.text());
  }
}
