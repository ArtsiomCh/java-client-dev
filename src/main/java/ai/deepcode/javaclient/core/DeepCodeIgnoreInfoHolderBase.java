package ai.deepcode.javaclient.core;

import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public abstract class DeepCodeIgnoreInfoHolderBase {

  private final HashContentUtilsBase hashContentUtils;

  protected DeepCodeIgnoreInfoHolderBase(
      @NotNull HashContentUtilsBase hashContentUtils) {
    this.hashContentUtils = hashContentUtils;
  }

  private static final Map<Object, Set<String>> map_dcignore2Regexps = new ConcurrentHashMap<>();
  private static final Map<Object, Set<String>> map_gitignore2Regexps = new ConcurrentHashMap<>();

  public boolean isDcIgnoredFile(@NotNull Object file) {
    return map_dcignore2Regexps.entrySet().stream()
        .filter(e -> inScope(e.getKey(), file))
        .flatMap(e -> e.getValue().stream())
        .anyMatch(getFilePath(file)::matches);
  }

  public boolean isGitIgnoredFile(@NotNull Object file) {
    return map_gitignore2Regexps.entrySet().stream()
        .filter(e -> inScope(e.getKey(), file))
        .flatMap(e -> e.getValue().stream())
        .anyMatch(getFilePath(file)::matches);
  }

  protected abstract String getFilePath(@NotNull Object file);

  private boolean inScope(@NotNull Object ignoreFile, @NotNull Object fileToCheck) {
    return getFilePath(fileToCheck).startsWith(getDirPath(ignoreFile));
  };

  public boolean is_ignoreFile(@NotNull Object file) {
    return is_dcignoreFile(file) || is_gitignoreFile(file);
  }

  protected abstract String getFileName(@NotNull Object file);

  public boolean is_dcignoreFile(@NotNull Object file) {
    return getFileName(file).equals(".dcignore");
  }

  public boolean is_gitignoreFile(@NotNull Object file) {
    return getFileName(file).equals(".gitignore");
  }

  public void remove_dcignoreFileContent(@NotNull Object file) {
    map_dcignore2Regexps.remove(file);
  }

  public void remove_gitignoreFileContent(@NotNull Object file) {
    map_gitignore2Regexps.remove(file);
  }

  public void removeProject(@NotNull Object project) {
    map_dcignore2Regexps.forEach((file, _set) -> {
      if (getProjectOfFile(file).equals(project)) map_dcignore2Regexps.remove(file);
    });
    map_gitignore2Regexps.forEach((file, _set) -> {
      if (getProjectOfFile(file).equals(project)) map_gitignore2Regexps.remove(file);
    });
  }

  protected abstract Object getProjectOfFile(@NotNull Object file);

  public void update_dcignoreFileContent(@NotNull Object file) {
    map_dcignore2Regexps.put(file, parse_ignoreFile2Regexps(file));
  }

  public void update_gitignoreFileContent(@NotNull Object file) {
    map_gitignore2Regexps.put(file, parse_ignoreFile2Regexps(file));
  }

  protected abstract String getDirPath(@NotNull Object file);

  private Set<String> parse_ignoreFile2Regexps(@NotNull Object file) {
    Set<String> result = new HashSet<>();
    String basePath = getDirPath(file);
    String lineSeparator = "[\n\r]";
    final String fileText = hashContentUtils.doGetFileContent(file);
    for (String line : fileText.split(lineSeparator)) {

      // https://git-scm.com/docs/gitignore#_pattern_format
      // todo: `!` negation not implemented yet
      line = line.trim();
      if (line.isEmpty() || line.startsWith("#")) continue;

      String prefix = basePath;
      // If there is a separator at the beginning or middle (or both) of the pattern, then the
      // pattern is relative to the directory level of the particular .gitignore file itself.
      // Otherwise the pattern may also match at any level below the .gitignore level.
      int indexBegMidSepar = line.substring(0, line.length() - 1).indexOf('/');
      if (indexBegMidSepar != 0) prefix += "/";
      if (indexBegMidSepar == -1) {
        prefix += ".*";
      } else if (line.endsWith("/*") || line.endsWith("/**")) {
        int indexLastSepar = line.lastIndexOf('/');
        if (indexBegMidSepar == indexLastSepar) prefix += ".*";
      }

      // If there is a separator at the end of the pattern then the pattern will only match
      // directories, otherwise the pattern can match both files and directories.
      String postfix =
          (line.endsWith("/"))
              ? ".+" // should be dir
              : "(/.+)?"; // could be dir or file

      String body =
          line.replace(".", "\\.")
              // An asterisk "*" matches anything except a slash.
              .replace("*", "[^/]*")
              // The character "?" matches any one character except "/".
              .replace("?", "[^/]?")
              // A slash followed by two consecutive asterisks then a slash matches zero or more
              // directories. For example, "a/**/b" matches "a/b", "a/x/b", "a/x/y/b" and so on.
              // A trailing "/**" matches everything inside. For example, "abc/**" matches all
              // files inside directory "abc", relative to the location of the .gitignore file,
              // with infinite depth.
              .replace("[^/]*[^/]*", ".*");

      result.add(prefix + body + postfix);
    }
    return result;
  }
}
