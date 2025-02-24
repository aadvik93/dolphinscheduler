/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.dolphinscheduler.api.permission;

import static java.util.stream.Collectors.toSet;

import org.apache.dolphinscheduler.common.enums.AuthorizationType;
import org.apache.dolphinscheduler.common.enums.UserType;
import org.apache.dolphinscheduler.dao.entity.AccessToken;
import org.apache.dolphinscheduler.dao.entity.AlertGroup;
import org.apache.dolphinscheduler.dao.entity.DataSource;
import org.apache.dolphinscheduler.dao.entity.Environment;
import org.apache.dolphinscheduler.dao.entity.K8sNamespace;
import org.apache.dolphinscheduler.dao.entity.Project;
import org.apache.dolphinscheduler.dao.entity.Queue;
import org.apache.dolphinscheduler.dao.entity.TaskGroup;
import org.apache.dolphinscheduler.dao.entity.Tenant;
import org.apache.dolphinscheduler.dao.entity.User;
import org.apache.dolphinscheduler.dao.entity.WorkerGroup;
import org.apache.dolphinscheduler.dao.mapper.AccessTokenMapper;
import org.apache.dolphinscheduler.dao.mapper.AlertGroupMapper;
import org.apache.dolphinscheduler.dao.mapper.AlertPluginInstanceMapper;
import org.apache.dolphinscheduler.dao.mapper.DataSourceMapper;
import org.apache.dolphinscheduler.dao.mapper.EnvironmentMapper;
import org.apache.dolphinscheduler.dao.mapper.K8sNamespaceMapper;
import org.apache.dolphinscheduler.dao.mapper.ProjectMapper;
import org.apache.dolphinscheduler.dao.mapper.QueueMapper;
import org.apache.dolphinscheduler.dao.mapper.TaskGroupMapper;
import org.apache.dolphinscheduler.dao.mapper.TenantMapper;
import org.apache.dolphinscheduler.dao.mapper.WorkerGroupMapper;
import org.apache.dolphinscheduler.dao.repository.UserDao;
import org.apache.dolphinscheduler.service.process.ProcessService;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

@Component
public class ResourcePermissionCheckServiceImpl
        implements
            ResourcePermissionCheckService<Object>,
            ApplicationContextAware {

    @Autowired
    private ProcessService processService;

    public static final Map<AuthorizationType, ResourceAcquisitionAndPermissionCheck<?>> RESOURCE_LIST_MAP =
            new ConcurrentHashMap<>();

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        for (ResourceAcquisitionAndPermissionCheck<?> authorizedResourceList : applicationContext
                .getBeansOfType(ResourceAcquisitionAndPermissionCheck.class).values()) {
            List<AuthorizationType> authorizationTypes = authorizedResourceList.authorizationTypes();
            authorizationTypes.forEach(auth -> RESOURCE_LIST_MAP.put(auth, authorizedResourceList));
        }
    }

    @Override
    public boolean resourcePermissionCheck(Object authorizationType, Object[] needChecks, Integer userId,
                                           Logger logger) {
        if (Objects.nonNull(needChecks) && needChecks.length > 0) {
            Set<?> originResSet = new HashSet<>(Arrays.asList(needChecks));
            Set<?> ownResSets = RESOURCE_LIST_MAP.get(authorizationType).listAuthorizedResourceIds(userId, logger);
            boolean checkResult = ownResSets != null && ownResSets.containsAll(originResSet);
            if (!checkResult) {
                logger.warn("User does not have resource permission on associated resources, userId:{}", userId);
            }
            return checkResult;
        }
        return true;
    }

    @Override
    public boolean operationPermissionCheck(Object authorizationType, Integer userId,
                                            String permissionKey, Logger logger) {
        User user = processService.getUserById(userId);
        if (user == null) {
            logger.error("User does not exist, userId:{}.", userId);
            return false;
        }
        if (user.getUserType().equals(UserType.ADMIN_USER)) {
            return true;
        }
        return RESOURCE_LIST_MAP.get(authorizationType).permissionCheck(userId, permissionKey, logger);
    }

    @Override
    public boolean functionDisabled() {
        return false;
    }

    @Override
    public Set<Object> userOwnedResourceIdsAcquisition(Object authorizationType, Integer userId, Logger logger) {
        User user = processService.getUserById(userId);
        if (user == null) {
            logger.error("User does not exist, userId:{}.", userId);
            return Collections.emptySet();
        }
        return (Set<Object>) RESOURCE_LIST_MAP.get(authorizationType).listAuthorizedResourceIds(
                user.getUserType().equals(UserType.ADMIN_USER) ? 0 : userId, logger);
    }

    @Component
    public static class QueueResourcePermissionCheck implements ResourceAcquisitionAndPermissionCheck<Integer> {

        private final QueueMapper queueMapper;

        public QueueResourcePermissionCheck(QueueMapper queueMapper) {
            this.queueMapper = queueMapper;
        }

        @Override
        public List<AuthorizationType> authorizationTypes() {
            return Collections.singletonList(AuthorizationType.QUEUE);
        }

        @Override
        public boolean permissionCheck(int userId, String permissionKey, Logger logger) {
            return false;
        }

        @Override
        public Set<Integer> listAuthorizedResourceIds(int userId, Logger logger) {
            if (userId != 0) {
                return Collections.emptySet();
            }
            List<Queue> queues = queueMapper.selectList(null);
            return queues.stream().map(Queue::getId).collect(toSet());
        }
    }

    @Component
    public static class ProjectsResourcePermissionCheck implements ResourceAcquisitionAndPermissionCheck<Integer> {

        private final ProjectMapper projectMapper;

        public ProjectsResourcePermissionCheck(ProjectMapper projectMapper) {
            this.projectMapper = projectMapper;
        }

        @Override
        public List<AuthorizationType> authorizationTypes() {
            return Collections.singletonList(AuthorizationType.PROJECTS);
        }

        @Override
        public boolean permissionCheck(int userId, String permissionKey, Logger logger) {
            // all users can create projects
            return true;
        }

        @Override
        public Set<Integer> listAuthorizedResourceIds(int userId, Logger logger) {
            return projectMapper.listAuthorizedProjects(userId, null).stream().map(Project::getId).collect(toSet());
        }
    }

    @Component
    public static class TaskGroupPermissionCheck implements ResourceAcquisitionAndPermissionCheck<Integer> {

        private final TaskGroupMapper taskGroupMapper;

        public TaskGroupPermissionCheck(TaskGroupMapper taskGroupMapper) {
            this.taskGroupMapper = taskGroupMapper;
        }

        @Override
        public List<AuthorizationType> authorizationTypes() {
            return Collections.singletonList(AuthorizationType.TASK_GROUP);
        }

        @Override
        public Set<Integer> listAuthorizedResourceIds(int userId, Logger logger) {
            List<TaskGroup> taskGroupList = taskGroupMapper.listAuthorizedResource(userId);
            return taskGroupList.stream().map(TaskGroup::getId).collect(Collectors.toSet());
        }

        @Override
        public boolean permissionCheck(int userId, String permissionKey, Logger logger) {
            return true;
        }
    }

    @Component
    public static class K8sNamespaceResourcePermissionCheck implements ResourceAcquisitionAndPermissionCheck<Integer> {

        private final K8sNamespaceMapper k8sNamespaceMapper;

        public K8sNamespaceResourcePermissionCheck(K8sNamespaceMapper k8sNamespaceMapper) {
            this.k8sNamespaceMapper = k8sNamespaceMapper;
        }

        @Override
        public List<AuthorizationType> authorizationTypes() {
            return Collections.singletonList(AuthorizationType.K8S_NAMESPACE);
        }

        @Override
        public boolean permissionCheck(int userId, String url, Logger logger) {
            return false;
        }

        @Override
        public Set<Integer> listAuthorizedResourceIds(int userId, Logger logger) {
            List<K8sNamespace> k8sNamespaces = k8sNamespaceMapper.queryAuthedNamespaceListByUserId(userId);
            return k8sNamespaces.stream().map(K8sNamespace::getId).collect(Collectors.toSet());
        }
    }

    @Component
    public static class EnvironmentResourcePermissionCheck implements ResourceAcquisitionAndPermissionCheck<Integer> {

        private final EnvironmentMapper environmentMapper;

        private final UserDao userDao;

        public EnvironmentResourcePermissionCheck(EnvironmentMapper environmentMapper, UserDao userDao) {
            this.environmentMapper = environmentMapper;
            this.userDao = userDao;
        }

        @Override
        public List<AuthorizationType> authorizationTypes() {
            return Collections.singletonList(AuthorizationType.ENVIRONMENT);
        }

        @Override
        public boolean permissionCheck(int userId, String url, Logger logger) {
            User user = userDao.queryById(userId);
            if (user == null) {
                logger.error("User does not exist, userId:{}.", userId);
                return false;
            }
            return user.getUserType() == UserType.ADMIN_USER;
        }

        @Override
        public Set<Integer> listAuthorizedResourceIds(int userId, Logger logger) {
            List<Environment> environments = environmentMapper.queryAllEnvironmentList();
            return environments.stream().map(Environment::getId).collect(Collectors.toSet());
        }
    }

    @Component
    public static class WorkerGroupResourcePermissionCheck implements ResourceAcquisitionAndPermissionCheck<Integer> {

        private final WorkerGroupMapper workerGroupMapper;

        public WorkerGroupResourcePermissionCheck(WorkerGroupMapper workerGroupMapper) {
            this.workerGroupMapper = workerGroupMapper;
        }

        @Override
        public List<AuthorizationType> authorizationTypes() {
            return Collections.singletonList(AuthorizationType.WORKER_GROUP);
        }

        @Override
        public boolean permissionCheck(int userId, String url, Logger logger) {
            return false;
        }

        @Override
        public Set<Integer> listAuthorizedResourceIds(int userId, Logger logger) {
            List<WorkerGroup> workerGroups = workerGroupMapper.queryAllWorkerGroup();
            return workerGroups.stream().map(WorkerGroup::getId).collect(Collectors.toSet());
        }
    }

    /**
     * AlertPluginInstance Resource
     */
    @Component
    public static class AlertPluginInstanceResourcePermissionCheck
            implements
                ResourceAcquisitionAndPermissionCheck<Integer> {

        private final AlertPluginInstanceMapper alertPluginInstanceMapper;

        public AlertPluginInstanceResourcePermissionCheck(AlertPluginInstanceMapper alertPluginInstanceMapper) {
            this.alertPluginInstanceMapper = alertPluginInstanceMapper;
        }

        @Override
        public List<AuthorizationType> authorizationTypes() {
            return Collections.singletonList(AuthorizationType.ALERT_PLUGIN_INSTANCE);
        }

        @Override
        public boolean permissionCheck(int userId, String url, Logger logger) {
            return false;
        }

        @Override
        public Set<Integer> listAuthorizedResourceIds(int userId, Logger logger) {
            return Collections.emptySet();
        }
    }

    /**
     * AlertPluginInstance Resource
     */
    @Component
    public static class AlertGroupResourcePermissionCheck implements ResourceAcquisitionAndPermissionCheck<Integer> {

        private final AlertGroupMapper alertGroupMapper;

        public AlertGroupResourcePermissionCheck(AlertGroupMapper alertGroupMapper) {
            this.alertGroupMapper = alertGroupMapper;
        }

        @Override
        public List<AuthorizationType> authorizationTypes() {
            return Collections.singletonList(AuthorizationType.ALERT_GROUP);
        }

        @Override
        public boolean permissionCheck(int userId, String url, Logger logger) {
            return false;
        }

        @Override
        public Set<Integer> listAuthorizedResourceIds(int userId, Logger logger) {
            List<AlertGroup> alertGroupList = alertGroupMapper.queryAllGroupList();
            return alertGroupList.stream().map(AlertGroup::getId).collect(toSet());
        }
    }

    /**
     * Tenant Resource
     */
    @Component
    public static class TenantResourcePermissionCheck implements ResourceAcquisitionAndPermissionCheck<Integer> {

        private final TenantMapper tenantMapper;

        public TenantResourcePermissionCheck(TenantMapper tenantMapper) {
            this.tenantMapper = tenantMapper;
        }

        @Override
        public List<AuthorizationType> authorizationTypes() {
            return Collections.singletonList(AuthorizationType.TENANT);
        }

        @Override
        public boolean permissionCheck(int userId, String url, Logger logger) {
            return false;
        }

        @Override
        public Set<Integer> listAuthorizedResourceIds(int userId, Logger logger) {
            List<Tenant> tenantList = tenantMapper.queryAll();
            return tenantList.stream().map(Tenant::getId).collect(Collectors.toSet());
        }
    }

    /**
     * DataSource Resource
     */
    @Component
    public static class DataSourceResourcePermissionCheck implements ResourceAcquisitionAndPermissionCheck<Integer> {

        private final DataSourceMapper dataSourceMapper;

        public DataSourceResourcePermissionCheck(DataSourceMapper dataSourceMapper) {
            this.dataSourceMapper = dataSourceMapper;
        }

        @Override
        public List<AuthorizationType> authorizationTypes() {
            return Collections.singletonList(AuthorizationType.DATASOURCE);
        }

        @Override
        public boolean permissionCheck(int userId, String url, Logger logger) {
            return true;
        }

        @Override
        public Set<Integer> listAuthorizedResourceIds(int userId, Logger logger) {
            return dataSourceMapper.listAuthorizedDataSource(userId, null).stream().map(DataSource::getId)
                    .collect(toSet());
        }
    }

    /**
     * AccessToken Resource
     */
    @Component
    public static class AccessTokenResourcePermissionCheck implements ResourceAcquisitionAndPermissionCheck<Integer> {

        private final AccessTokenMapper accessTokenMapper;

        public AccessTokenResourcePermissionCheck(AccessTokenMapper accessTokenMapper) {
            this.accessTokenMapper = accessTokenMapper;
        }

        @Override
        public List<AuthorizationType> authorizationTypes() {
            return Collections.singletonList(AuthorizationType.ACCESS_TOKEN);
        }

        @Override
        public boolean permissionCheck(int userId, String url, Logger logger) {
            return false;
        }

        @Override
        public Set<Integer> listAuthorizedResourceIds(int userId, Logger logger) {
            return accessTokenMapper.listAuthorizedAccessToken(userId, null).stream().map(AccessToken::getId)
                    .collect(toSet());
        }
    }

    interface ResourceAcquisitionAndPermissionCheck<T> {

        /**
         * authorization types
         *
         * @return
         */
        List<AuthorizationType> authorizationTypes();

        /**
         * get all resources under the user (no admin)
         *
         * @param userId
         * @return
         */
        Set<T> listAuthorizedResourceIds(int userId, Logger logger);

        /**
         * permission check
         *
         * @param userId
         * @return
         */
        boolean permissionCheck(int userId, String permissionKey, Logger logger);

    }
}
