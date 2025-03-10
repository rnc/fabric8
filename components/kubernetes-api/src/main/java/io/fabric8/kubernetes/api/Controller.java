/**
 *  Copyright 2005-2014 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.kubernetes.api;

import io.fabric8.kubernetes.api.model.KubernetesList;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.ReplicationController;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.DeploymentConfig;
import io.fabric8.openshift.api.model.ImageStream;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.template.Template;
import io.fabric8.utils.Files;
import io.fabric8.utils.Objects;
import io.fabric8.utils.Strings;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.util.List;
import java.util.Map;

import static io.fabric8.kubernetes.api.KubernetesHelper.*;

/**
 * Applies DTOs to the current Kubernetes master
 */
public class Controller {
    private static final transient Logger LOG = LoggerFactory.getLogger(Controller.class);

    private final KubernetesClient kubernetes;
    private Map<String, Pod> podMap;
    private Map<String, ReplicationController> replicationControllerMap;
    private Map<String, Service> serviceMap;
    private boolean throwExceptionOnError;
    private boolean allowCreate = true;
    private boolean recreateMode;
    private boolean servicesOnlyMode;
    private boolean ignoreServiceMode;

    public Controller() {
        this(new KubernetesClient());
    }

    public Controller(KubernetesClient kubernetes) {
        this.kubernetes = kubernetes;
    }

    public String apply(File file) throws Exception {
        String ext = Files.getFileExtension(file);

        if ("yaml".equalsIgnoreCase(ext)) {
            return applyYaml(file);
        } else if ("json".equalsIgnoreCase(ext)) {
            return applyJson(file);
        } else {
            throw new IllegalArgumentException("Unknown file type " + ext);
        }
    }

    /**
     * Applies the given JSON to the underlying REST APIs in a single operation without needing to explicitly parse first.
     */
    public String applyJson(byte[] json) throws Exception {
        Object dto = loadJson(json);
        apply(dto, "REST call");
        return "";
    }

    /**
     * Applies the given JSON to the underlying REST APIs in a single operation without needing to explicitly parse first.
     */
    public String applyJson(String json) throws Exception {
        Object dto = loadJson(json);
        apply(dto, "REST call");
        return "";
    }

    /**
     * Applies the given JSON to the underlying REST APIs in a single operation without needing to explicitly parse first.
     */
    public String applyJson(File json) throws Exception {
        Object dto = loadJson(json);
        apply(dto, "REST call");
        return "";
    }

    /**
     * Applies the given YAML to the underlying REST APIs in a single operation without needing to explicitly parse first.
     */
    public String applyYaml(String yaml) throws Exception {
        String json = convertYamlToJson(yaml);
        Object dto = loadJson(json);
        apply(dto, "REST call");
        return "";
    }

    /**
     * Applies the given YAML to the underlying REST APIs in a single operation without needing to explicitly parse first.
     */
    public String applyYaml(File yaml) throws Exception {
        String json = convertYamlToJson(yaml);
        Object dto = loadJson(json);
        apply(dto, "REST call");
        return "";
    }

    private String convertYamlToJson(String yamlString) throws FileNotFoundException {
        Yaml yaml = new Yaml();

        Map<String, Object> map = (Map<String, Object>) yaml.load(yamlString);
        JSONObject jsonObject = new JSONObject(map);

        return jsonObject.toString();
    }

    private String convertYamlToJson(File yamlFile) throws FileNotFoundException {
        Yaml yaml = new Yaml();
        FileInputStream fstream = new FileInputStream(yamlFile);

        Map<String, Object> map = (Map<String, Object>) yaml.load(fstream);
        JSONObject jsonObject = new JSONObject(map);

        return jsonObject.toString();
    }

    /**
     * Applies the given JSON to the underlying REST APIs in a single operation without needing to explicitly parse first.
     */
    public String applyJson(InputStream json) throws Exception {
        Object dto = loadJson(json);
        apply(dto, "REST call");
        return "";
    }

    /**
     * Applies the given DTOs onto the Kubernetes master
     */
    public void apply(Object dto, String sourceName) throws Exception {
        if (dto instanceof List) {
            List list = (List) dto;
            for (Object element : list) {
                if (dto == element) {
                    LOG.warn("Found recursive nested object for " + dto + " of class: " + dto.getClass().getName());
                    continue;
                }
                apply(element, sourceName);
            }
        } else if (dto instanceof KubernetesList) {
            applyList((KubernetesList) dto, sourceName);
        } else if (dto != null) {
            applyEntity(dto, sourceName);
        }
    }

    /**
     * Applies the given DTOs onto the Kubernetes master
     */
    public void applyEntity(Object dto, String sourceName) throws Exception {
        if (dto instanceof Pod) {
            applyPod((Pod) dto, sourceName);
        } else if (dto instanceof ReplicationController) {
            applyReplicationController((ReplicationController) dto, sourceName);
        } else if (dto instanceof Service) {
            applyService((Service) dto, sourceName);
        } else if (dto instanceof Route) {
            applyRoute((Route) dto, sourceName);
        } else if (dto instanceof BuildConfig) {
            applyBuildConfig((BuildConfig) dto, sourceName);
        } else if (dto instanceof DeploymentConfig) {
            applyDeploymentConfig((DeploymentConfig) dto, sourceName);
        } else if (dto instanceof ImageStream) {
            applyImageStream((ImageStream) dto, sourceName);
        } else if (dto instanceof OAuthClient) {
            applyOAuthClient((OAuthClient) dto, sourceName);
        } else if (dto instanceof Template) {
            applyTemplate((Template) dto, sourceName);
        } else {
            throw new IllegalArgumentException("Unknown entity type " + dto);
        }
    }

    public void applyOAuthClient(OAuthClient entity, String sourceName) {
        String id = getName(entity);
        Objects.notNull(id, "No name for " + entity + " " + sourceName);
        if (isServicesOnlyMode()) {
            LOG.debug("Only processing Services right now so ignoring OAuthClient: " + id);
            return;
        }
        OAuthClient old = kubernetes.getOAuthClient(id);
        if (isRunning(old)) {
            if (UserConfigurationCompare.configEqual(entity, old)) {
                LOG.info("OAuthClient hasn't changed so not doing anything");
            } else {
                if (isRecreateMode()) {
                    kubernetes.deleteOAuthClient(id);
                    doCreateOAuthClient(entity, sourceName);
                } else {
                    try {
                        Object answer = kubernetes.updateOAuthClient(id, entity);
                        LOG.info("Updated pod result: " + answer);
                    } catch (Exception e) {
                        onApplyError("Failed to update pod from " + sourceName + ". " + e + ". " + entity, e);
                    }
                }
            }
        } else {
            if (!isAllowCreate()) {
                LOG.warn("Creation disabled so not creating an OAuthClient from " + sourceName + " name " + getName(entity));
            } else {
                doCreateOAuthClient(entity, sourceName);
            }
        }
    }

    protected void doCreateOAuthClient(OAuthClient entity, String sourceName) {
        Object result = null;
        try {
            result = kubernetes.createOAuthClient(entity);
        } catch (Exception e) {
            onApplyError("Failed to create OAuthClient from " + sourceName + ". " + e + ". " + entity, e);
        }
    }

    public void applyTemplate(Template entity, String sourceName) {
        Object result = processTemplate(entity, sourceName);
        if (result != null) {
            try {
                applyEntity(result, sourceName);
            } catch (Exception e) {
                onApplyError("Failed to apply result of template expansion from " + sourceName + ". " + e + ". " + result, e);
            }
        }
    }

    public Object processTemplate(Template entity, String sourceName) {
        String id = getName(entity);
        Objects.notNull(id, "No name for " + entity + " " + sourceName);
        String namespace = KubernetesHelper.getNamespace(entity);
        LOG.info("Creating Template " + namespace + ":" + id + " " + summaryText(entity));
        Object result = null;
        try {
            String json = kubernetes.createTemplate(entity, namespace);
            LOG.info("Template processed into: " + json);
            result = loadJson(json);
            printSummary(result);
        } catch (Exception e) {
            onApplyError("Failed to create controller from " + sourceName + ". " + e + ". " + entity, e);
        }
        return result;
    }


    protected void printSummary(Object kubeResource) throws IOException {
        if (kubeResource != null) {
            LOG.debug("  " + kubeResource.getClass().getSimpleName() + " " + kubeResource);
        }
        if (kubeResource instanceof Template) {
            Template template = (Template) kubeResource;
            String id = getName(template);
            LOG.info("  Template " + id + " " + summaryText(template));
            printSummary(template.getObjects());
            return;
        }
        List<Object> list = toItemList(kubeResource);
        for (Object object : list) {
            if (object != null) {
                if (object == list) {
                    LOG.warn("Ignoring recursive list " + list);
                    continue;
                } else if (object instanceof List) {
                    printSummary(object);
                } else {
                    String kind = object.getClass().getSimpleName();
                    String id = getObjectId(object);
                    LOG.info("    " + kind + " " + id + " " + summaryText(object));
                }
            }
        }
    }

    public void applyRoute(Route entity, String sourceName) {
        String id = getName(entity);
        Objects.notNull(id, "No name for " + entity + " " + sourceName);
        String namespace = KubernetesHelper.getNamespace(entity);
        if (Strings.isNullOrBlank(namespace)) {
            namespace = kubernetes.getNamespace();
        }
        Route route = kubernetes.findRoute(id, namespace);
        if (route == null) {
            try {
                LOG.info("Creating Route " + namespace + ":" + id);
                kubernetes.createRoute(entity, namespace);
            } catch (Exception e) {
                onApplyError("Failed to create BuildConfig from " + sourceName + ". " + e + ". " + entity, e);
            }
        }
    }

    public void applyBuildConfig(BuildConfig entity, String sourceName) {
        try {
            kubernetes.createBuildConfig(entity, getNamespace());
        } catch (Exception e) {
            onApplyError("Failed to create BuildConfig from " + sourceName + ". " + e, e);
        }
    }

    public void applyDeploymentConfig(DeploymentConfig entity, String sourceName) {
        try {
            kubernetes.createDeploymentConfig(entity, getNamespace());
        } catch (Exception e) {
            onApplyError("Failed to create DeploymentConfig from " + sourceName + ". " + e, e);
        }
    }

    public void applyImageStream(ImageStream entity, String sourceName) {
        try {
            kubernetes.createImageStream(entity, getNamespace());
        } catch (Exception e) {
            onApplyError("Failed to create BuildConfig from " + sourceName + ". " + e, e);
        }
    }

    public void applyList(KubernetesList list, String sourceName) throws Exception {
        List<Object> entities = list.getItems();
        if (entities != null) {
            for (Object entity : entities) {
                applyEntity(entity, sourceName);
            }
        }
    }

    public void applyService(Service service, String sourceName) throws Exception {
        String namespace = getNamespace();
        String id = getName(service);
        Objects.notNull(id, "No name for " + service + " " + sourceName);
        if (isIgnoreServiceMode()) {
            LOG.debug("Ignoring Service: " + namespace + ":" + id);
            return;
        }
        if (serviceMap == null) {
            serviceMap = getServiceMap(kubernetes, namespace);
        }
        Service old = serviceMap.get(id);
        if (isRunning(old)) {
            if (UserConfigurationCompare.configEqual(service, old)) {
                LOG.info("Service hasn't changed so not doing anything");
            } else {
                if (isRecreateMode()) {
                    kubernetes.deleteService(service, namespace);
                    doCreateService(service, namespace, sourceName);
                } else {
                    LOG.info("Updating a service from " + sourceName);
                    try {
                        Object answer = kubernetes.updateService(id, service, namespace);
                        LOG.info("Updated service: " + answer);
                    } catch (Exception e) {
                        onApplyError("Failed to update controller from " + sourceName + ". " + e + ". " + service, e);
                    }
                }
            }
        } else {
            if (!isAllowCreate()) {
                LOG.warn("Creation disabled so not creating a service from " + sourceName + " namespace " + namespace + " name " + getName(service));
            } else {
                doCreateService(service, namespace, sourceName);
            }
        }
    }

    protected void doCreateService(Service service, String namespace, String sourceName) {
        LOG.info("Creating a service from " + sourceName + " namespace " + namespace + " name " + getName(service));
        try {
            Object answer;
            if (Strings.isNotBlank(namespace)) {
                answer = kubernetes.createService(service, namespace);
            } else {
                answer = kubernetes.createService(service);
            }
            LOG.info("Created service: " + answer);
        } catch (Exception e) {
            onApplyError("Failed to create service from " + sourceName + ". " + e + ". " + service, e);
        }
    }

    public void applyReplicationController(ReplicationController replicationController, String sourceName) throws Exception {
        String namespace = getNamespace();
        String id = getName(replicationController);
        Objects.notNull(id, "No name for " + replicationController + " " + sourceName);
        if (isServicesOnlyMode()) {
            LOG.debug("Only processing Services right now so ignoring ReplicationController: " + namespace + ":" + id);
            return;
        }
        if (replicationControllerMap == null) {
            replicationControllerMap = getReplicationControllerMap(kubernetes, namespace);
        }
        ReplicationController old = replicationControllerMap.get(id);
        if (isRunning(old)) {
            if (UserConfigurationCompare.configEqual(replicationController, old)) {
                LOG.info("ReplicationController hasn't changed so not doing anything");
            } else {
                if (isRecreateMode()) {
                    kubernetes.deleteReplicationControllerAndPods(replicationController, namespace);
                    doCreateReplicationController(replicationController, namespace, sourceName);
                } else {
                    LOG.info("Updating replicationController from " + sourceName + " namespace " + namespace + " name " + getName(replicationController));
                    try {
                        Object answer = kubernetes.updateReplicationController(id, replicationController);
                        LOG.info("Updated replicationController: " + answer);
                    } catch (Exception e) {
                        onApplyError("Failed to update replicationController from " + sourceName + ". " + e + ". " + replicationController, e);
                    }
                }
            }
        } else {
            if (!isAllowCreate()) {
                LOG.warn("Creation disabled so not creating a replicationController from " + sourceName + " namespace " + namespace + " name " + getName(replicationController));
            } else {
                doCreateReplicationController(replicationController, namespace, sourceName);
            }
        }
    }

    protected void doCreateReplicationController(ReplicationController replicationController, String namespace, String sourceName) {
        LOG.info("Creating a replicationController from " + sourceName + " namespace " + namespace + " name " + getName(replicationController));
        try {
            Object answer;
            if (Strings.isNotBlank(namespace)) {
                answer = kubernetes.createReplicationController(replicationController, namespace);
            } else {
                answer = kubernetes.createReplicationController(replicationController);
            }
            LOG.info("Created replicationController: " + answer);
        } catch (Exception e) {
            onApplyError("Failed to create replicationController from " + sourceName + ". " + e + ". " + replicationController, e);
        }
    }

    public void applyPod(Pod pod, String sourceName) throws Exception {
        String namespace = getNamespace();
        String id = getName(pod);
        Objects.notNull(id, "No name for " + pod + " " + sourceName);
        if (isServicesOnlyMode()) {
            LOG.debug("Only processing Services right now so ignoring Pod: " + namespace + ":" + id);
            return;
        }
        if (podMap == null) {
            podMap = getPodMap(kubernetes, namespace);
        }
        Pod old = podMap.get(id);
        if (isRunning(old)) {
            if (UserConfigurationCompare.configEqual(pod, old)) {
                LOG.info("Pod hasn't changed so not doing anything");
            } else {
                if (isRecreateMode()) {
                    kubernetes.deletePod(pod, namespace);
                    doCreatePod(pod, namespace, sourceName);
                } else {
                    LOG.info("Updating a pod from " + sourceName + " namespace " + namespace + " name " + getName(pod));
                    try {
                        Object answer = kubernetes.updatePod(id, pod);
                        LOG.info("Updated pod result: " + answer);
                    } catch (Exception e) {
                        onApplyError("Failed to update pod from " + sourceName + ". " + e + ". " + pod, e);
                    }
                }
            }
        } else {
            if (!isAllowCreate()) {
                LOG.warn("Creation disabled so not creating a pod from " + sourceName + " namespace " + namespace + " name " + getName(pod));
            } else {
                doCreatePod(pod, namespace, sourceName);
            }
        }
    }

    protected void doCreatePod(Pod pod, String namespace, String sourceName) {
        LOG.info("Creating a pod from " + sourceName + " namespace " + namespace + " name " + getName(pod));
        try {
            Object answer;
            if (Strings.isNotBlank(namespace)) {
                answer = kubernetes.createPod(pod, namespace);
            } else {
                answer = kubernetes.createPod(pod);
            }
            LOG.info("Created pod result: " + answer);
        } catch (Exception e) {
            onApplyError("Failed to create pod from " + sourceName + ". " + e + ". " + pod, e);
        }
    }

    public String getNamespace() {
        return kubernetes.getNamespace();
    }

    public void setNamespace(String namespace) {
        kubernetes.setNamespace(namespace);
    }

    public boolean isThrowExceptionOnError() {
        return throwExceptionOnError;
    }

    public void setThrowExceptionOnError(boolean throwExceptionOnError) {
        this.throwExceptionOnError = throwExceptionOnError;
    }

    protected boolean isRunning(OAuthClient entity) {
        return entity != null;
    }

    protected boolean isRunning(Pod entity) {
        // TODO we could maybe ignore failed services?
        return entity != null;
    }

    protected boolean isRunning(ReplicationController entity) {
        // TODO we could maybe ignore failed services?
        return entity != null;
    }

    protected boolean isRunning(Service entity) {
        // TODO we could maybe ignore failed services?
        return entity != null;
    }


    /**
     * Logs an error applying some JSON to Kubernetes and optionally throws an exception
     */
    protected void onApplyError(String message, Exception e) {
        LOG.error(message, e);
        if (throwExceptionOnError) {
            throw new RuntimeException(message, e);
        }
    }

    /**
     * Returns true if this controller allows new resources to be created in the given namespace
     */
    public boolean isAllowCreate() {
        return allowCreate;
    }

    public void setAllowCreate(boolean allowCreate) {
        this.allowCreate = allowCreate;
    }

    /**
     * If enabled then updates are performed by deleting the resource first then creating it
     */
    public boolean isRecreateMode() {
        return recreateMode;
    }

    public void setRecreateMode(boolean recreateMode) {
        this.recreateMode = recreateMode;
    }

    public void setServicesOnlyMode(boolean servicesOnlyMode) {
        this.servicesOnlyMode = servicesOnlyMode;
    }

    /**
     * If enabled then only services are created/updated to allow services to be created/updated across
     * a number of apps before any pods/replication controllers are updated
     */
    public boolean isServicesOnlyMode() {
        return servicesOnlyMode;
    }

    /**
     * If enabled then all services are ignored to avoid them being recreated. This is useful if you want to
     * recreate ReplicationControllers and Pods but leave Services as they are to avoid the portalIP addresses
     * changing
     */
    public boolean isIgnoreServiceMode() {
        return ignoreServiceMode;
    }

    public void setIgnoreServiceMode(boolean ignoreServiceMode) {
        this.ignoreServiceMode = ignoreServiceMode;
    }
}
