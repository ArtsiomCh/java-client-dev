package ai.deepcode.javaclient.core;

import ai.deepcode.javaclient.DeepCodeRestApi;
import ai.deepcode.javaclient.requests.*;
import ai.deepcode.javaclient.responses.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

public abstract class AnalysisDataBase {

  private final PlatformDependentUtilsBase pdUtils;
  private final HashContentUtilsBase hashContentUtils;
  private final DeepCodeParamsBase deepCodeParams;
  private final DCLoggerBase dcLogger;

  protected AnalysisDataBase(
      @NotNull PlatformDependentUtilsBase platformDependentUtils,
      @NotNull HashContentUtilsBase hashContentUtils,
      @NotNull DeepCodeParamsBase deepCodeParams,
      @NotNull DCLoggerBase dcLogger) {
    this.pdUtils = platformDependentUtils;
    this.hashContentUtils = hashContentUtils;
    this.deepCodeParams = deepCodeParams;
    this.dcLogger = dcLogger;
    UPLOADING_FILES_TEXT = dcLogger.presentableName + ": Uploading files to the server... ";
    PREPARE_FILES_TEXT = dcLogger.presentableName + ": Preparing files for upload... ";
    WAITING_FOR_ANALYSIS_TEXT = dcLogger.presentableName + ": Waiting for analysis from server... ";
  }

  private final String UPLOADING_FILES_TEXT;
  private final String PREPARE_FILES_TEXT;
  private final String WAITING_FOR_ANALYSIS_TEXT;

  private static final Map<Object, List<SuggestionForFile>> EMPTY_MAP = Collections.emptyMap();
  private static final Map<Object, String> mapProject2analysisUrl = new ConcurrentHashMap<>();

  // todo: keep few latest file versions (Guava com.google.common.cache.CacheBuilder ?)
  private static final Map<Object, List<SuggestionForFile>> mapFile2Suggestions =
      new ConcurrentHashMap<>();

  private static final Map<Object, String> mapProject2BundleId = new ConcurrentHashMap<>();

  // Mutex need to be requested to change mapFile2Suggestions
  private static final ReentrantLock MUTEX = new ReentrantLock();

  /** see getAnalysis() below} */
  @NotNull
  public List<SuggestionForFile> getAnalysis(@NotNull Object file) {
    return getAnalysis(Collections.singleton(file)).getOrDefault(file, Collections.emptyList());
  }

  /**
   * Return Suggestions mapped to Files.
   *
   * <p>Look into cached results ONLY.
   *
   * @param files
   * @return
   */
  @NotNull
  public Map<Object, List<SuggestionForFile>> getAnalysis(@NotNull Collection<Object> files) {
    if (files.isEmpty()) {
      dcLogger.logWarn("getAnalysis requested for empty list of files");
      return Collections.emptyMap();
    }
    Map<Object, List<SuggestionForFile>> result = new HashMap<>();
    final Collection<Object> brokenKeys = new ArrayList<>();
    for (Object file : files) {
      List<SuggestionForFile> suggestions = mapFile2Suggestions.get(file);
      if (suggestions != null) {
        result.put(file, suggestions);
      } else {
        brokenKeys.add(file);
      }
    }
    if (!brokenKeys.isEmpty()) {
      dcLogger.logWarn(
          "Suggestions not found for " + brokenKeys.size() + " files: " + brokenKeys.toString());
    }
    return result;
  }

  public String getAnalysisUrl(@NotNull Object project) {
    return mapProject2analysisUrl.computeIfAbsent(project, p -> "");
  }

  public boolean addProjectToCache(@NotNull Object project) {
    return mapProject2BundleId.putIfAbsent(project, "") == null;
  }

  public Set<Object> getAllCachedProject() {
    return mapProject2BundleId.keySet();
  }

  public void removeFilesFromCache(@NotNull Collection<Object> files) {
    try {
      dcLogger.logInfo("Request to remove from cache " + files.size() + " files: " + files);
      // todo: do we really need mutex here?
      MUTEX.lock();
      dcLogger.logInfo("MUTEX LOCK");
      int removeCounter = 0;
      for (Object file : files) {
        if (file != null && isFileInCache(file)) {
          mapFile2Suggestions.remove(file);
          hashContentUtils.removeFileHashContent(file);
          removeCounter++;
        }
      }
      dcLogger.logInfo(
          "Actually removed from cache: "
              + removeCounter
              + " files. Were not in cache: "
              + (files.size() - removeCounter));
    } finally {
      dcLogger.logInfo("MUTEX RELEASED");
      MUTEX.unlock();
    }
    updateUIonFilesRemovalFromCache(files);
  }

  protected abstract void updateUIonFilesRemovalFromCache(@NotNull Collection<Object> files);

  public void removeProjectFromCaches(@NotNull Object project) {
    dcLogger.logInfo("Caches clearance requested for project: " + project);
    hashContentUtils.removeProjectHashContent(project);
    if (mapProject2BundleId.remove(project) != null) {
      dcLogger.logInfo("Removed from cache: " + project);
    }
    removeFilesFromCache(cachedFilesOfProject(project));
  }

  private Collection<Object> cachedFilesOfProject(@NotNull Object project) {
    return mapFile2Suggestions.keySet().stream()
        .filter(file -> pdUtils.getProject(file).equals(project))
        .collect(Collectors.toList());
  }

  private static final Set<Object> updateInProgress = Collections.synchronizedSet(new HashSet<>());

  public void setUpdateInProgress(@NotNull Object project) {
    synchronized (updateInProgress) {
      updateInProgress.add(project);
    }
  }

  public void unsetUpdateInProgress(@NotNull Object project) {
    synchronized (updateInProgress) {
      updateInProgress.remove(project);
    }
  }

  public boolean isUpdateAnalysisInProgress(@NotNull Object project) {
    synchronized (updateInProgress) {
      return updateInProgress.contains(project);
    }
  }

  public boolean isProjectNOTAnalysed(@NotNull Object project) {
    return !getAllCachedProject().contains(project);
  }

  public void waitForUpdateAnalysisFinish(@NotNull Object project, @Nullable Object progress) {
    while (isUpdateAnalysisInProgress(project)) {
      // delay should be less or equal to runInBackgroundCancellable delay
      pdUtils.delay(pdUtils.DEFAULT_DELAY_SMALL, progress);
    }
  }

  /*
    public static void updateCachedResultsForFile(@NotNull Object psiFile) {
      updateCachedResultsForFiles(Collections.singleton(psiFile), Collections.emptyList());
    }
  */

  public void updateCachedResultsForFiles(
      @NotNull Object project,
      @NotNull Collection<Object> psiFiles,
      @NotNull Collection<Object> filesToRemove,
      @NotNull Object progress) {
    if (psiFiles.isEmpty() && filesToRemove.isEmpty()) {
      dcLogger.logWarn("updateCachedResultsForFiles requested for empty list of files");
      return;
    }
    dcLogger.logInfo("Update requested for " + psiFiles.size() + " files: " + psiFiles.toString());
    if (!deepCodeParams.consentGiven(project)) {
      dcLogger.logWarn("Consent check fail! Project: " + pdUtils.getProjectName(project));
      return;
    }
    try {
      MUTEX.lock();
      dcLogger.logInfo("MUTEX LOCK");
      setUpdateInProgress(project);
      Collection<Object> filesToProceed =
          psiFiles.stream()
              .filter(Objects::nonNull)
              .filter(file -> !mapFile2Suggestions.containsKey(file))
              .collect(Collectors.toSet());
      if (!filesToProceed.isEmpty()) {
        // collection already checked to be not empty
        final Object firstFile = filesToProceed.iterator().next();
        final String fileHash = hashContentUtils.getHash(firstFile);
        dcLogger.logInfo(
            "Files to proceed (not found in cache): "
                + filesToProceed.size()
                + "\nHash for first file "
                + pdUtils.getFileName(firstFile)
                + " ["
                + fileHash
                + "]");
        if (filesToProceed.size() == 1 && filesToRemove.isEmpty()) {
          // if only one file updates then its most likely from annotator. So we need to get
          // suggestions asap:
          // we do that through createBundle with fileContent
          mapFile2Suggestions.put(firstFile, retrieveSuggestions(firstFile, progress));
          // and then request normal extendBundle later to synchronize results on server
          pdUtils.runInBackgroundCancellable(
              firstFile,
              "Synchronize analysis result with server...",
              (progress1) ->
                  retrieveSuggestions(project, filesToProceed, filesToRemove, progress1));
        } else {
          mapFile2Suggestions.putAll(
              retrieveSuggestions(project, filesToProceed, filesToRemove, progress));
        }
      } else if (!filesToRemove.isEmpty()) {
        dcLogger.logInfo(
            "Files to remove: " + filesToRemove.size() + " files: " + filesToRemove.toString());
        retrieveSuggestions(project, filesToProceed, filesToRemove, progress);
      } else {
        dcLogger.logWarn(
            "Nothing to update for " + psiFiles.size() + " files: " + psiFiles.toString());
      }
      unsetUpdateInProgress(project);
      pdUtils.refreshPanel(project);
      // ServiceManager.getService(project, myTodoView.class).refresh();
    } finally {
      // if (filesToProceed != null && !filesToProceed.isEmpty())
      dcLogger.logInfo("MUTEX RELEASED");
      MUTEX.unlock();
    }
  }

  // todo? propagate userActionNeeded through whole methods call chain
  // fixme: should be project based
  private static boolean loginRequested = false;
  private static boolean isNotSucceedWarnShown = false;

  private boolean isNotSucceed(@NotNull Object project, EmptyResponse response, String message) {
    if (response.getStatusCode() == 200) {
      return loginRequested = isNotSucceedWarnShown = false;
    } else if (response.getStatusCode() == 401) {
      pdUtils.isLogged(project, !loginRequested);
      loginRequested = isNotSucceedWarnShown = true;
    }
    final String fullMessage =
        message + response.getStatusCode() + " " + response.getStatusDescription();
    dcLogger.logWarn(fullMessage);
    if (!isNotSucceedWarnShown) {
      if (response.getStatusCode() / 100 == 4) {
        pdUtils.showWarn("Network request fail: " + fullMessage, project);
      } else {
        pdUtils.showWarn(
            "Server internal error. Please, try again later.\n" + fullMessage, project);
      }
      isNotSucceedWarnShown = true;
    }
    return true;
  }

  static final int MAX_BUNDLE_SIZE = 4000000; // bytes

  /** Perform costly network request. <b>No cache checks!</b> */
  @NotNull
  private Map<Object, List<SuggestionForFile>> retrieveSuggestions(
      @NotNull Object project,
      @NotNull Collection<Object> filesToProceed,
      @NotNull Collection<Object> filesToRemove,
      @NotNull Object progress) {
    if (filesToProceed.isEmpty() && filesToRemove.isEmpty()) {
      dcLogger.logWarn("Both filesToProceed and filesToRemove are empty");
      return EMPTY_MAP;
    }
    // no needs to check login here as it will be checked anyway during every api response's check
    // if (!LoginUtils.isLogged(project, false)) return EMPTY_MAP;

    List<String> missingFiles = createBundleStep(project, filesToProceed, filesToRemove, progress);

    uploadFilesStep(project, filesToProceed, missingFiles, progress);

    // ---------------------------------------- Get Analysis
    final String bundleId = mapProject2BundleId.getOrDefault(project, "");
    if (bundleId.isEmpty()) return EMPTY_MAP; // no sense to proceed without bundleId
    long startTime = System.currentTimeMillis();
    pdUtils.progressSetText(progress, WAITING_FOR_ANALYSIS_TEXT);
    pdUtils.progressCheckCanceled(progress);
    GetAnalysisResponse getAnalysisResponse = doGetAnalysis(project, bundleId, progress);
    Map<Object, List<SuggestionForFile>> result =
        parseGetAnalysisResponse(project, filesToProceed, getAnalysisResponse, progress);
    dcLogger.logInfo(
        "--- Get Analysis took: " + (System.currentTimeMillis() - startTime) + " milliseconds");
    return result;
  }

  /**
   * Perform costly network request. <b>No cache checks!</b>
   *
   * @return missingFiles
   */
  private List<String> createBundleStep(
      @NotNull Object project,
      @NotNull Collection<Object> filesToProceed,
      @NotNull Collection<Object> filesToRemove,
      @NotNull Object progress) {
    long startTime = System.currentTimeMillis();
    pdUtils.progressSetText(progress, PREPARE_FILES_TEXT);
    dcLogger.logInfo(PREPARE_FILES_TEXT);
    pdUtils.progressCheckCanceled(progress);
    Map<String, String> mapPath2Hash = new HashMap<>();
    long sizePath2Hash = 0;
    int fileCounter = 0;
    int totalFiles = filesToProceed.size();
    for (Object file : filesToProceed) {
      hashContentUtils.removeFileHashContent(file);
      pdUtils.progressCheckCanceled(progress);
      pdUtils.progressSetFraction(progress, ((double) fileCounter++) / totalFiles);
      pdUtils.progressSetText(
          progress, PREPARE_FILES_TEXT + fileCounter + " of " + totalFiles + " files done.");

      final String path = pdUtils.getDeepCodedFilePath(file);
      // info("getHash requested");
      final String hash = hashContentUtils.getHash(file);
      if (fileCounter == 1)
        dcLogger.logInfo("First file to proceed: \npath = " + path + "\nhash = " + hash);

      mapPath2Hash.put(path, hash);
      sizePath2Hash += (path.length() + hash.length()) * 2; // rough estimation of bytes occupied
      if (sizePath2Hash > MAX_BUNDLE_SIZE) {
        CreateBundleResponse tempBundleResponse =
            makeNewBundle(project, mapPath2Hash, Collections.emptyList());
        sizePath2Hash = 0;
        mapPath2Hash.clear();
      }
    }
    // todo break removeFiles in chunks less then MAX_BANDLE_SIZE
    //  needed ?? we do full rescan for large amount of files to remove
    CreateBundleResponse createBundleResponse = makeNewBundle(project, mapPath2Hash, filesToRemove);

    final String bundleId = createBundleResponse.getBundleId();

    List<String> missingFiles = createBundleResponse.getMissingFiles();
    dcLogger.logInfo(
        "--- Create/Extend Bundle took: "
            + (System.currentTimeMillis() - startTime)
            + " milliseconds"
            + "\nbundleId: "
            + bundleId
            + "\nmissingFiles: "
            + missingFiles.size());
    return missingFiles;
  }

  /** Perform costly network request. <b>No cache checks!</b> */
  private void uploadFilesStep(
      @NotNull Object project,
      @NotNull Collection<Object> filesToProceed,
      @NotNull List<String> missingFiles,
      @NotNull Object progress) {
    long startTime = System.currentTimeMillis();
    pdUtils.progressSetText(progress, UPLOADING_FILES_TEXT);
    pdUtils.progressCheckCanceled(progress);

    final String bundleId = mapProject2BundleId.getOrDefault(project, "");
    if (bundleId.isEmpty()) {
      dcLogger.logInfo("BundleId is empty");
    } else if (missingFiles.isEmpty()) {
      dcLogger.logInfo("No missingFiles to Upload");
    } else {
      final int attempts = 5;
      for (int counter = 0; counter < attempts; counter++) {
        uploadFiles(project, filesToProceed, missingFiles, bundleId, progress);
        missingFiles = checkBundle(project, bundleId);
        if (missingFiles.isEmpty()) {
          break;
        } else {
          dcLogger.logWarn(
              "Check Bundle found "
                  + missingFiles.size()
                  + " missingFiles (NOT uploaded), will try to upload "
                  + (attempts - counter)
                  + " more times:\nmissingFiles = "
                  + missingFiles);
        }
      }
    }
    dcLogger.logInfo(
        "--- Upload Files took: " + (System.currentTimeMillis() - startTime) + " milliseconds");
  }

  /** Perform costly network request. <b>No cache checks!</b> */
  @NotNull
  private List<SuggestionForFile> retrieveSuggestions(
      @NotNull Object file, @NotNull Object progress) {
    final Object project = pdUtils.getProject(file);
    List<SuggestionForFile> result;
    long startTime;
    // ---------------------------------------- Create Bundle
    startTime = System.currentTimeMillis();
    dcLogger.logInfo("Creating temporary Bundle from File content");
    pdUtils.progressCheckCanceled(progress);

    FileContent fileContent =
        new FileContent(pdUtils.getDeepCodedFilePath(file), hashContentUtils.getFileContent(file));
    FileContentRequest fileContentRequest =
        new FileContentRequest(Collections.singletonList(fileContent));

    // todo?? it might be cheaper on server side to extend one temporary bundle
    //  rather then create the new one every time
    final CreateBundleResponse createBundleResponse =
        DeepCodeRestApi.createBundle(deepCodeParams.getSessionToken(), fileContentRequest);
    isNotSucceed(project, createBundleResponse, "Bad Create/Extend Bundle request: ");

    final String bundleId = createBundleResponse.getBundleId();
    if (bundleId.isEmpty()) return Collections.emptyList(); // no sense to proceed without bundleId

    List<String> missingFiles = createBundleResponse.getMissingFiles();
    dcLogger.logInfo(
        "--- Create temporary Bundle took: "
            + (System.currentTimeMillis() - startTime)
            + " milliseconds"
            + "\nbundleId: "
            + bundleId
            + "\nmissingFiles: "
            + missingFiles);
    if (!missingFiles.isEmpty()) dcLogger.logWarn("missingFiles is NOT empty!");

    // ---------------------------------------- Get Analysis
    pdUtils.progressCheckCanceled(progress);
    startTime = System.currentTimeMillis();
    GetAnalysisResponse getAnalysisResponse = doGetAnalysis(project, bundleId, progress);
    result =
        parseGetAnalysisResponse(
                project, Collections.singleton(file), getAnalysisResponse, progress)
            .getOrDefault(file, Collections.emptyList());
    mapProject2analysisUrl.put(project, "");

    dcLogger.logInfo(
        "--- Get Analysis took: " + (System.currentTimeMillis() - startTime) + " milliseconds");
    //    progress.stop();
    return result;
  }

  private void uploadFiles(
      @NotNull Object project,
      @NotNull Collection<Object> filesToProceed,
      @NotNull List<String> missingFiles,
      @NotNull String bundleId,
      @NotNull Object progress) {
    Map<String, Object> mapPath2File =
        filesToProceed.stream().collect(Collectors.toMap(pdUtils::getDeepCodedFilePath, it -> it));
    int fileCounter = 0;
    int totalFiles = missingFiles.size();
    long fileChunkSize = 0;
    int brokenMissingFilesCount = 0;
    String brokenMissingFilesMessage = "";
    List<Object> filesChunk = new ArrayList<>();
    for (String filePath : missingFiles) {
      pdUtils.progressCheckCanceled(progress);
      pdUtils.progressSetFraction(progress, ((double) fileCounter++) / totalFiles);
      pdUtils.progressSetText(
          progress, UPLOADING_FILES_TEXT + fileCounter + " of " + totalFiles + " files done.");

      Object file = mapPath2File.get(filePath);
      if (file == null) {
        if (brokenMissingFilesCount == 0) {
          brokenMissingFilesMessage =
              " files requested in missingFiles not found in filesToProceed (skipped to upload)."
                  + "\nFirst broken missingFile: "
                  + filePath;
        }
        brokenMissingFilesCount++;
        continue;
      }
      final long fileSize = pdUtils.getFileSize(file); // .getVirtualFile().getLength();
      if (fileChunkSize + fileSize > MAX_BUNDLE_SIZE) {
        dcLogger.logInfo("Files-chunk size: " + fileChunkSize);
        doUploadFiles(project, filesChunk, bundleId, progress);
        fileChunkSize = 0;
        filesChunk.clear();
      }
      fileChunkSize += fileSize;
      filesChunk.add(file);
    }
    if (brokenMissingFilesCount > 0)
      dcLogger.logWarn(brokenMissingFilesCount + brokenMissingFilesMessage);
    dcLogger.logInfo("Last filesToProceed-chunk size: " + fileChunkSize);
    doUploadFiles(project, filesChunk, bundleId, progress);
  }

  /**
   * Checks the status of a bundle: if there are still missing files after uploading
   *
   * @return list of the current missingFiles.
   */
  @NotNull
  private List<String> checkBundle(@NotNull Object project, @NotNull String bundleId) {
    CreateBundleResponse checkBundleResponse =
        DeepCodeRestApi.checkBundle(deepCodeParams.getSessionToken(), bundleId);
    if (isNotSucceed(project, checkBundleResponse, "Bad CheckBundle request: ")) {
      return Collections.emptyList();
    }
    return checkBundleResponse.getMissingFiles();
  }

  private CreateBundleResponse makeNewBundle(
      @NotNull Object project,
      @NotNull Map<String, String> mapPath2Hash,
      @NotNull Collection<Object> filesToRemove) {
    final FileHashRequest fileHashRequest = new FileHashRequest(mapPath2Hash);
    final String parentBundleId = mapProject2BundleId.getOrDefault(project, "");
    if (!parentBundleId.isEmpty()
        && !filesToRemove.isEmpty()
        && mapPath2Hash.isEmpty()
        && filesToRemove.containsAll(cachedFilesOfProject(project))) {
      dcLogger.logWarn(
          "Attempt to Extending a bundle by removing all the parent bundle's files: "
              + filesToRemove);
    }
    List<String> removedFiles =
        filesToRemove.stream().map(pdUtils::getDeepCodedFilePath).collect(Collectors.toList());
    String message =
        (parentBundleId.isEmpty()
                ? "Creating new Bundle with "
                : "Extending existing Bundle [" + parentBundleId + "] with ")
            + mapPath2Hash.size()
            + " files"
            + (removedFiles.isEmpty() ? "" : " and remove " + removedFiles.size() + " files");
    dcLogger.logInfo(message);
    // todo make network request in parallel with collecting data
    final CreateBundleResponse bundleResponse;
    // check if bundleID for the project already been created
    if (parentBundleId.isEmpty())
      bundleResponse =
          DeepCodeRestApi.createBundle(deepCodeParams.getSessionToken(), fileHashRequest);
    else {
      bundleResponse =
          DeepCodeRestApi.extendBundle(
              deepCodeParams.getSessionToken(),
              parentBundleId,
              new ExtendBundleRequest(fileHashRequest.getFiles(), removedFiles));
    }
    String newBundleId = bundleResponse.getBundleId();
    // By man: "Extending a bundle by removing all the parent bundle's files is not allowed."
    // In reality new bundle returned with next bundleID:
    // .../DEEPCODE_PRIVATE_BUNDLE/0000000000000000000000000000000000000000000000000000000000000000
    if (newBundleId.endsWith(
        "/DEEPCODE_PRIVATE_BUNDLE/0000000000000000000000000000000000000000000000000000000000000000")) {
      newBundleId = "";
    }
    mapProject2BundleId.put(project, newBundleId);
    isNotSucceed(project, bundleResponse, "Bad Create/Extend Bundle request: ");
    // just make new bundle in case of 404 Parent bundle has expired
    return (bundleResponse.getStatusCode() == 404)
        ? makeNewBundle(project, mapPath2Hash, filesToRemove)
        : bundleResponse;
  }

  private void doUploadFiles(
      @NotNull Object project,
      @NotNull Collection<Object> psiFiles,
      @NotNull String bundleId,
      @NotNull Object progress) {
    dcLogger.logInfo("Uploading " + psiFiles.size() + " files... ");
    if (psiFiles.isEmpty()) return;
    List<FileHash2ContentRequest> listHash2Content = new ArrayList<>(psiFiles.size());
    for (Object psiFile : psiFiles) {
      pdUtils.progressCheckCanceled(progress);
      listHash2Content.add(
          new FileHash2ContentRequest(
              hashContentUtils.getHash(psiFile), hashContentUtils.getFileContent(psiFile)));
    }
    if (listHash2Content.isEmpty()) return;

    // todo make network request in parallel with collecting data
    EmptyResponse uploadFilesResponse =
        DeepCodeRestApi.UploadFiles(deepCodeParams.getSessionToken(), bundleId, listHash2Content);
    isNotSucceed(project, uploadFilesResponse, "Bad UploadFiles request: ");
  }

  @NotNull
  private GetAnalysisResponse doGetAnalysis(
      @NotNull Object project, @NotNull String bundleId, @NotNull Object progress) {
    GetAnalysisResponse response;
    int counter = 0;
    final int timeout = 100; // seconds
    final int attempts = timeout * 1000 / pdUtils.DEFAULT_DELAY;
    do {
      if (counter > 0) pdUtils.delay(pdUtils.DEFAULT_DELAY, progress);
      response =
          DeepCodeRestApi.getAnalysis(
              deepCodeParams.getSessionToken(),
              bundleId,
              deepCodeParams.getMinSeverity(),
              deepCodeParams.useLinter());

      pdUtils.progressCheckCanceled(progress);
      dcLogger.logInfo(response.toString());
      if (isNotSucceed(project, response, "Bad GetAnalysis request: "))
        return new GetAnalysisResponse();

      double responseProgress = response.getProgress();
      if (responseProgress <= 0 || responseProgress > 1)
        responseProgress = ((double) counter) / attempts;
      pdUtils.progressSetFraction(progress, responseProgress);
      pdUtils.progressSetText(
          progress, WAITING_FOR_ANALYSIS_TEXT + (int) (responseProgress * 100) + "% done");

      if (counter >= attempts) {
        dcLogger.logWarn("Timeout expire for waiting analysis results.");
        /*
                DeepCodeNotifications.showWarn(
                    "Can't get analysis results from the server. Network or server internal error. Please, try again later.",
                    project);
        */
        break;
      }

      if (response.getStatus().equals("FAILED")) {
        dcLogger.logWarn("FAILED getAnalysis request.");
        // if Failed then we have inconsistent caches, better to do full rescan
        pdUtils.doFullRescan(project);
        /*if (!RunUtils.isFullRescanRequested(project)) {
          RunUtils.rescanInBackgroundCancellableDelayed(project, 500, false);
        }*/
        break;
      }

      counter++;
    } while (!response.getStatus().equals("DONE")
    // !!!! keep commented in production, for debug only: to emulate long processing
    // || counter < 10
    );
    return response;
  }

  @NotNull
  private Map<Object, List<SuggestionForFile>> parseGetAnalysisResponse(
      @NotNull Object project,
      @NotNull Collection<Object> files,
      GetAnalysisResponse response,
      @NotNull Object progress) {
    Map<Object, List<SuggestionForFile>> result = new HashMap<>();
    if (!response.getStatus().equals("DONE")) return EMPTY_MAP;
    AnalysisResults analysisResults = response.getAnalysisResults();
    mapProject2analysisUrl.put(project, response.getAnalysisURL());
    if (analysisResults == null) {
      dcLogger.logWarn("AnalysisResults is null for: " + response);
      return EMPTY_MAP;
    }
    for (Object file : files) {
      // fixme iterate over analysisResults.getFiles() to reduce empty passes
      final String deepCodedFilePath = pdUtils.getDeepCodedFilePath(file);
      FileSuggestions fileSuggestions = analysisResults.getFiles().get(deepCodedFilePath);
      if (fileSuggestions == null) {
        result.put(file, Collections.emptyList());
        continue;
      }
      final Suggestions suggestions = analysisResults.getSuggestions();
      if (suggestions == null) {
        dcLogger.logWarn("Suggestions is empty for: " + response);
        return EMPTY_MAP;
      }
      pdUtils.progressCheckCanceled(progress);

      final List<SuggestionForFile> mySuggestions = new ArrayList<>();
      for (String suggestionIndex : fileSuggestions.keySet()) {
        final Suggestion suggestion = suggestions.get(suggestionIndex);
        if (suggestion == null) {
          dcLogger.logWarn(
              "Suggestion not found for suggestionIndex: "
                  + suggestionIndex
                  + "\nGetAnalysisResponse: "
                  + response);
          return EMPTY_MAP;
        }

        final List<MyTextRange> ranges = new ArrayList<>();
        for (FilePosition filePosition : fileSuggestions.get(suggestionIndex)) {

          final Map<MyTextRange, List<MyTextRange>> markers =
              new LinkedHashMap<>(); // order should be preserved
          for (Marker marker : filePosition.getMarkers()) {
            final MyTextRange msgRange =
                new MyTextRange(marker.getMsg().get(0), marker.getMsg().get(1) + 1);
            final List<MyTextRange> positions =
                marker.getPos().stream()
                    .map(it -> parsePosition2MyTextRange(it, file, Collections.emptyMap()))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            markers.put(msgRange, positions);
          }
          final MyTextRange suggestionRange = parsePosition2MyTextRange(filePosition, file, markers);
          if (suggestionRange != null) ranges.add(suggestionRange);
        }

        mySuggestions.add(
            new SuggestionForFile(
                suggestion.getId(),
                suggestion.getRule(),
                suggestion.getMessage(),
                suggestion.getSeverity(),
                suggestion.getRepoDatasetSize(),
                suggestion.getExampleCommitFixes(),
                ranges));
      }
      result.put(file, mySuggestions);
    }
    return result;
  }

  @Nullable
  private MyTextRange parsePosition2MyTextRange(
      @NotNull final Position position,
      @NotNull final Object file,
      @NotNull final Map<MyTextRange, List<MyTextRange>> markers) {

    final int startRow = position.getRows().get(0);
    final int endRow = position.getRows().get(1);
    final int startCol = position.getCols().get(0) - 1; // inclusive
    final int endCol = position.getCols().get(1);

    if (startRow <= 0 || endRow <= 0 || startCol < 0 || endCol < 0) {
      final String deepCodedFilePath = pdUtils.getDeepCodedFilePath(file);
      dcLogger.logWarn(
          "Incorrect " + position + "\nin file: " + deepCodedFilePath);
      return null;
    }

    final int mLineStartOffset = pdUtils.getLineStartOffset(file, startRow - 1); // to 0-based
    final int mLineEndOffset = pdUtils.getLineStartOffset(file, endRow - 1);

    return new MyTextRange(
        mLineStartOffset + startCol,
        mLineEndOffset + endCol,
        startRow,
        endRow,
        startCol,
        endCol,
        markers);
  }

  private FileContent createFileContent(Object file) {
    return new FileContent(
        pdUtils.getDeepCodedFilePath(file), hashContentUtils.getFileContent(file));
  }

  public Set<Object> getAllFilesWithSuggestions(@NotNull final Object project) {
    return mapFile2Suggestions.entrySet().stream()
        .filter(e -> pdUtils.getProject(e.getKey()).equals(project))
        .filter(e -> !e.getValue().isEmpty())
        .map(Map.Entry::getKey)
        .collect(Collectors.toSet());
  }

  public boolean isFileInCache(@NotNull Object psiFile) {
    return mapFile2Suggestions.containsKey(psiFile);
  }

  /** Remove project from all Caches and <b>CANCEL</b> all background tasks for it */
  public void resetCachesAndTasks(@Nullable final Object project) {
    final Set<Object> projects =
        (project == null) ? getAllCachedProject() : Collections.singleton(project);
    for (Object prj : projects) {
      // lets all running ProgressIndicators release MUTEX first
      pdUtils.cancelRunningIndicators(prj);
      removeProjectFromCaches(prj);
      pdUtils.refreshPanel(prj); // ServiceManager.getService(prj, myTodoView.class).refresh();
      mapProject2analysisUrl.put(prj, "");
    }
  }
}
