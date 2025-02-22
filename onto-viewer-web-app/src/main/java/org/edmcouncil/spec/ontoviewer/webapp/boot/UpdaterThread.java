package org.edmcouncil.spec.ontoviewer.webapp.boot;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.edmcouncil.spec.ontoviewer.configloader.configuration.model.ConfigurationData;
import org.edmcouncil.spec.ontoviewer.configloader.configuration.service.ApplicationConfigurationService;
import org.edmcouncil.spec.ontoviewer.configloader.utils.files.FileSystemService;
import org.edmcouncil.spec.ontoviewer.core.ontology.OntologyManager;
import org.edmcouncil.spec.ontoviewer.core.ontology.data.handler.DeprecatedHandler;
import org.edmcouncil.spec.ontoviewer.core.ontology.data.handler.ResourcesPopulate;
import org.edmcouncil.spec.ontoviewer.core.ontology.data.label.LabelProvider;
import org.edmcouncil.spec.ontoviewer.core.ontology.loader.AutoOntologyLoader;
import org.edmcouncil.spec.ontoviewer.core.ontology.loader.listener.MissingImport;
import org.edmcouncil.spec.ontoviewer.core.ontology.loader.zip.ViewerZipFilesOperations;
import org.edmcouncil.spec.ontoviewer.core.ontology.scope.ScopeIriOntology;
import org.edmcouncil.spec.ontoviewer.core.ontology.stats.OntologyStatsManager;
import org.edmcouncil.spec.ontoviewer.core.ontology.updater.model.InterruptUpdate;
import org.edmcouncil.spec.ontoviewer.core.ontology.updater.model.UpdateJob;
import org.edmcouncil.spec.ontoviewer.core.ontology.updater.model.UpdateJobStatus;
import org.edmcouncil.spec.ontoviewer.core.ontology.updater.util.UpdaterOperation;
import org.edmcouncil.spec.ontoviewer.core.ontology.data.handler.VersionIriHandler;
import org.edmcouncil.spec.ontoviewer.webapp.search.LuceneSearcher;
import org.edmcouncil.spec.ontoviewer.webapp.service.FallbackService;
import org.edmcouncil.spec.ontoviewer.webapp.service.IntegrationService;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.UnloadableImportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class UpdaterThread extends Thread implements Thread.UncaughtExceptionHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(UpdaterThread.class);
  private static final String INTERRUPT_MESSAGE = "Interrupts this update. New update request.";

  private final OntologyManager ontologyManager;
  private final FileSystemService fileSystemService;
  private final UpdateBlocker blocker;
  private final ScopeIriOntology scopeIriOntology;
  private final OntologyStatsManager ontologyStatsManager;
  private final LuceneSearcher luceneSearcher;
  private final ApplicationConfigurationService applicationConfigurationService;
  private final FallbackService fallbackService;
  private UpdateJob job;
  private final ResourcesPopulate resourcesPopulate;
  private final DeprecatedHandler deprecatedHandler;
  private final VersionIriHandler versionIriHandler;
  private final LabelProvider labelProvider;
  private final IntegrationService integrationService;

  protected UpdaterThread(OntologyManager ontologyManager,
      FileSystemService fileSystemService,
      UpdateBlocker blocker,
      UpdateJob job,
      ScopeIriOntology scopeIriOntology,
      OntologyStatsManager osm,
      LuceneSearcher luceneSearcher,
      ApplicationConfigurationService applicationConfigurationService,
      ResourcesPopulate resourcesPopulate,
      FallbackService fallbackService,
      DeprecatedHandler deprecatedHandler,
      VersionIriHandler versionIriHandler, LabelProvider labelProvider,
      IntegrationService integrationService) {
    this.ontologyManager = ontologyManager;
    this.fileSystemService = fileSystemService;
    this.blocker = blocker;
    this.job = job;
    this.scopeIriOntology = scopeIriOntology;
    this.ontologyStatsManager = osm;
    this.luceneSearcher = luceneSearcher;
    this.applicationConfigurationService = applicationConfigurationService;
    this.resourcesPopulate = resourcesPopulate;
    this.fallbackService = fallbackService;
    this.deprecatedHandler = deprecatedHandler;
    this.versionIriHandler = versionIriHandler;
    this.labelProvider = labelProvider;
    this.integrationService = integrationService;
    this.setName("UpdateThread-" + job.getId());
  }

  @Override
  public void run() {
    while (true) {
      if (blocker.isUpdateNow()) {
        try {
          if (isInterrupt()) {
            UpdaterOperation.setJobStatusToError(job, INTERRUPT_MESSAGE);
            this.interrupt();
            return;
          }
          String msg = String.format("UpdateJob with id: %s waiting to end other updates",
              job.getId());
          UpdaterOperation.setJobStatusToWaiting(job, msg);
          LOGGER.debug(msg);
          //Wait for one sec so it doesn't print too fast
          Thread.sleep(1000);
        } catch (InterruptedException e) {
          e.printStackTrace();
          UpdaterOperation.setJobStatusToError(job, e.getMessage());
          return;
        }
      } else {
        blocker.setUpdateNow(Boolean.TRUE);
        break;
      }
    }

    try {
      if (isInterrupt()) {
        throw new InterruptUpdate();
      }

      job = UpdaterOperation.setJobStatusToInProgress(job);

      OWLOntology ontology = null;
      Map<IRI, IRI> iriToPathMapping = new HashMap<>();
      String msgError = null;

      LOGGER.info("File system manager created ? : {}", fileSystemService != null);

      applicationConfigurationService.reloadConfiguration();
      ConfigurationData configurationData = applicationConfigurationService.getConfigurationData();

      if (isInterrupt()) {
        throw new InterruptUpdate();
      }

      //ZIP Support
      ViewerZipFilesOperations viewerZipFilesOperations = new ViewerZipFilesOperations();
      Set<MissingImport> missingImports = viewerZipFilesOperations.prepareZipToLoad(
          applicationConfigurationService.getConfigurationData(),
          fileSystemService);

      //download ontology file/files
      //load ontology to var
      AutoOntologyLoader loader = new AutoOntologyLoader(configurationData, fileSystemService);
      try {
        var loadedOntologyData = loader.load();
        ontology = loadedOntologyData.getOntology();
        iriToPathMapping = loadedOntologyData.getIrisToPathsMapping();
      } catch (OWLOntologyCreationException ex) {
        msgError = ex.getMessage();
        LOGGER.error(
            "[ERROR]: Error when creating ontology. Stopping application. Exception: {} \n Message: {}",
            ex.getStackTrace(), ex.getMessage());
      }

      if (msgError != null) {
        LOGGER.error("[ERROR]: Cannot update, id {}", job.getId());
        job = UpdaterOperation.setJobStatusToError(job, msgError);
        blocker.setUpdateNow(Boolean.FALSE);
        return;
      }
      if (isInterrupt()) {
        throw new InterruptUpdate();
      }

      missingImports.addAll(loader.getMissingImportListenerImpl().getNotImportUri());
      LOGGER.info("Missing imports: {}", missingImports);

      Set<String> scopes = scopeIriOntology.getScopeIri(ontology);

      if (isInterrupt()) {
        throw new InterruptUpdate();
      }

      //block resourcess when swaping
      blocker.setBlockerStatus(Boolean.TRUE);

      scopeIriOntology.setScopes(scopes);

      ontologyManager.updateOntology(ontology);
      labelProvider.reload();
      integrationService.reload();

      ontologyManager.setMissingImports(missingImports);
      ontologyManager.setIriToPathMapping(iriToPathMapping);

      versionIriHandler.init(ontologyManager);
      deprecatedHandler.init();

      luceneSearcher.populateIndex();

      //load ontology resource must be here, data handler use label provider
      resourcesPopulate.populateOntologyResources();

      ontologyStatsManager.clear();
      ontologyStatsManager.generateStats(ontology);

      blocker.setBlockerStatus(Boolean.FALSE);

      job = UpdaterOperation.setJobStatusToDone(job);

      blocker.setUpdateNow(Boolean.FALSE);

      if (job.getId().equals(String.valueOf(0))) {
        blocker.setInitializeAppDone(Boolean.TRUE);
      }
      LOGGER.info("Application has started successfully.");
      fallbackService.sendRequestToNextInstance(job.getId());
    } catch (InterruptUpdate ex) {
      LOGGER.error("{}", ex.getStackTrace());
      UpdaterOperation.setJobStatusToError(job, INTERRUPT_MESSAGE);
      blocker.setUpdateNow(Boolean.FALSE);
      this.interrupt();
    } catch (NullPointerException | UnloadableImportException | IOException ex) {
      ex.printStackTrace();
      UpdaterOperation.setJobStatusToError(job, ex.getMessage());
      blocker.setUpdateNow(Boolean.FALSE);
      this.interrupt();
    } 
  }

  @Override
  public void uncaughtException(Thread t, Throwable e) {
    UpdaterOperation.setJobStatusToError(job, e.getMessage());
    LOGGER.error(e.getStackTrace().toString());
    blocker.setUpdateNow(Boolean.FALSE);
  }

  public UpdateJob getJob() {
    return job;
  }

  private Boolean isInterrupt() {
    return job.getStatus() == UpdateJobStatus.ERROR
        || job.getStatus() == UpdateJobStatus.INTERRUPT_IN_PROGRESS;
  }
}
