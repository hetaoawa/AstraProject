package top.hetaoawa.astra.api;

import java.util.List;

/** The matched command token, untouched argument text and whitespace-separated arguments. */
public record CommandArgs(String command, String text, List<String> arguments) {
  public CommandArgs {
    arguments = List.copyOf(arguments);
  }
}
