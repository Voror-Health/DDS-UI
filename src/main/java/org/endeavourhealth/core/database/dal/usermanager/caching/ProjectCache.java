package org.endeavourhealth.core.database.dal.usermanager.caching;


import org.endeavourhealth.core.database.dal.DalProvider;
import org.endeavourhealth.core.database.dal.datasharingmanager.ProjectApplicationPolicyDalI;
import org.endeavourhealth.core.database.dal.datasharingmanager.ProjectDalI;
import org.endeavourhealth.core.database.dal.datasharingmanager.models.JsonProject;
import org.endeavourhealth.core.database.rdbms.datasharingmanager.models.ProjectApplicationPolicyEntity;
import org.endeavourhealth.core.database.rdbms.datasharingmanager.models.ProjectEntity;
import org.endeavourhealth.ui.datasteward.DataStewardClient;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VHP override of common core's {@code ProjectCache} (same fully-qualified name: WEB-INF/classes is loaded before
 * WEB-INF/lib, so the library code calls this copy). Copied from vhp-queuereader's override. Only
 * {@link #getValidDistributionProjectsForPublisher} changed: SubscriberHelper's subscriber lookup (Exchange Audit's
 * protocol list, the Subscribers pages) asks vhp-datasteward instead of reading the old Data Sharing Manager
 * tables. Everything else is the library code, unchanged.
 */
public class ProjectCache {

    /** Proves (in ProjectCacheOverrideTest) that this copy, not the library's, is the one loaded. */
    @SuppressWarnings("unused")
    private static final String VHP_OVERRIDE = "datasteward";

    private static Map<String, ProjectEntity> projectMap = new ConcurrentHashMap<>();
    private static Map<String, JsonProject> jsonProjectMap = new ConcurrentHashMap<>();
    private static Map<String, String> projectApplicationPolicyMap = new ConcurrentHashMap<>();
    private static Map<String, List<ProjectEntity>> allProjectsForAllChildRegion = new ConcurrentHashMap<>();
    private static Map<String, List<String>> allPublishersForProjectWithSubCheck = new ConcurrentHashMap<>();
    private static Map<String, List<ProjectEntity>> allProjectsForSubscriberODS = new ConcurrentHashMap<>();
    private static Map<String, List<ProjectEntity>> validDistributionProjectsForPublisher = new ConcurrentHashMap<>();
    private static Map<String, List<String>> allPublishersForActiveProject = new ConcurrentHashMap<>();

    private static ProjectDalI repository = DalProvider.factoryDSMProjectDal();
    private static ProjectApplicationPolicyDalI ProjectAppPolicyRepository = DalProvider.factoryDSMProjectApplicationPolicyDal();

    public static List<ProjectEntity> getProjectDetails(List<String> projects) throws Exception {
        List<ProjectEntity> projectEntities = new ArrayList<>();
        List<String> missingProjects = new ArrayList<>();

        for (String org : projects) {
            ProjectEntity projInMap = projectMap.get(org);
            if (projInMap != null) {
                projectEntities.add(projInMap);
            } else {
                missingProjects.add(org);
            }
        }

        if (missingProjects.size() > 0) {
            List<ProjectEntity> entities = repository.getProjectsFromList(missingProjects);

            for (ProjectEntity org : entities) {
                projectMap.put(org.getUuid(), org);
                projectEntities.add(org);
            }
        }

        CacheManager.startScheduler();

        return projectEntities;

    }

    public static ProjectEntity getProjectDetails(String projectId) throws Exception {

        ProjectEntity projectEntity = projectMap.get(projectId);
        if (projectEntity == null) {
            projectEntity = repository.getProject(projectId);
            projectMap.put(projectEntity.getUuid(), projectEntity);
        }

        CacheManager.startScheduler();

        return projectEntity;

    }

    public static JsonProject getJsonProjectDetails(String projectId) throws Exception {

        JsonProject project = jsonProjectMap.get(projectId);
        if (project == null) {
            project = repository.getFullProjectJson(projectId);
            jsonProjectMap.put(project.getUuid(), project);
        }

        CacheManager.startScheduler();

        return project;

    }

    public static String getProjectApplicationPolicy(String projectId) throws Exception {

        String foundPolicy = projectApplicationPolicyMap.get(projectId);
        if (foundPolicy == null) {
            ProjectApplicationPolicyEntity policyApp = ProjectAppPolicyRepository.getProjectApplicationPolicyId(projectId);
            foundPolicy = policyApp.getApplicationPolicyId();
            projectApplicationPolicyMap.put(projectId, foundPolicy);
        }

        CacheManager.startScheduler();

        return foundPolicy;
    }

    public static List<ProjectEntity> getAllProjectsForAllChildRegions(String regionId) throws Exception {

        List<ProjectEntity> allProjects = allProjectsForAllChildRegion.get(regionId);
        if (allProjects == null) {
            allProjects = repository.getProjectsForRegion(regionId);
            allProjectsForAllChildRegion.put(regionId, allProjects);
        }

        CacheManager.startScheduler();

        return allProjects;
    }

    public static List<String> getAllPublishersForProjectWithSubscriberCheck(String projectId, String requesterOdsCode) throws Exception {
        String key = projectId + ":" + requesterOdsCode;

        List<String> pubOdsCodes = allPublishersForProjectWithSubCheck.get(key);
        if (pubOdsCodes == null) {
            pubOdsCodes = repository.getPublishersForProject(projectId, requesterOdsCode);
            allPublishersForProjectWithSubCheck.put(key, pubOdsCodes);
        }

        CacheManager.startScheduler();

        return pubOdsCodes;
    }

    public static List<String> getAllPublishersForValidProject(String projectId, boolean checkActive) throws Exception {
        String key = projectId + ":" + String.valueOf(checkActive);

        List<String> pubOdsCodes = allPublishersForActiveProject.get(key);
        if (pubOdsCodes == null) {
            pubOdsCodes = repository.getPublishersForProjectWithActiveCheck(projectId, checkActive);
            allPublishersForActiveProject.put(key, pubOdsCodes);
        }

        CacheManager.startScheduler();

        return pubOdsCodes;
    }

    public static List<ProjectEntity> getAllProjectsForSubscriberOrg(String odsCode) throws Exception {

        List<ProjectEntity> projects = allProjectsForSubscriberODS.get(odsCode);
        if (projects == null) {
            projects = repository.getAllProjectsForSubscriber(odsCode);
            allProjectsForSubscriberODS.put(odsCode, projects);
        }

        CacheManager.startScheduler();

        return projects;
    }

    /**
     * VHP: from vhp-datasteward ({@code GET /organisation/distributionProjects}), which applies the same filter the
     * DSM DAL did (distribution type, active, in date, authorised). DataStewardClient caches the answer, so the
     * {@code validDistributionProjectsForPublisher} map is no longer used. As before, null means an unknown ODS code.
     */
    public static List<ProjectEntity> getValidDistributionProjectsForPublisher(String publisherOdsCode) throws Exception {

        List<DataStewardClient.DistributionProject> projects
                = DataStewardClient.getInstance().getValidDistributionProjects(publisherOdsCode);
        if (projects == null) {
            return null;
        }

        List<ProjectEntity> validProjects = new ArrayList<>();
        for (DataStewardClient.DistributionProject project : projects) {
            validProjects.add(toEntity(project));
        }
        return validProjects;
    }

    private static ProjectEntity toEntity(DataStewardClient.DistributionProject project) {
        ProjectEntity entity = new ProjectEntity();
        entity.setUuid(project.uuid());
        entity.setName(project.name());
        entity.setConfigName(project.configName());
        entity.setProjectTypeId(project.projectTypeId());
        entity.setProjectStatusId(project.projectStatusId());
        entity.setStartDate(project.startDate() == null ? null : Date.valueOf(LocalDate.parse(project.startDate())));
        entity.setEndDate(project.endDate() == null ? null : Date.valueOf(LocalDate.parse(project.endDate())));
        entity.setAuthorisedBy(project.authorisedBy());
        entity.setAuthorisedDate(project.authorisedDate() == null ? null
                : Timestamp.valueOf(LocalDateTime.parse(project.authorisedDate())));
        return entity;
    }

    public static void clearProjectCache(String projectId) throws Exception {
        projectMap.remove(projectId);
        jsonProjectMap.remove(projectId);
        projectApplicationPolicyMap.remove(projectId);
        allProjectsForAllChildRegion.clear();
        allPublishersForProjectWithSubCheck.clear();
        allPublishersForActiveProject.clear();
    }

    public static void flushCache() throws Exception {
        projectMap.clear();
        jsonProjectMap.clear();
        projectApplicationPolicyMap.clear();
        allProjectsForAllChildRegion.clear();
        allPublishersForProjectWithSubCheck.clear();
        allProjectsForSubscriberODS.clear();
        validDistributionProjectsForPublisher.clear();
        allPublishersForActiveProject.clear();

        //VHP: distribution projects are cached by the DataSteward client (shared with OrganisationCache's DPA
        //answers, which OrganisationCache.flushCache clears as well)
        try {
            DataStewardClient.getInstance().clearCache();
        } catch (IllegalStateException notInitialised) {
            //nothing cached
        }
    }
}
