/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.ee.naming;

import java.util.HashSet;
import java.util.Set;

import org.jboss.as.ee.component.EEModuleDescription;
import org.jboss.as.ee.structure.DeploymentType;
import org.jboss.as.ee.structure.DeploymentTypeMarker;
import org.jboss.as.naming.ServiceBasedNamingStore;
import org.jboss.as.naming.ValueManagedReferenceFactory;
import org.jboss.as.naming.deployment.ContextNames;
import org.jboss.as.naming.service.BinderService;
import org.jboss.as.naming.service.NamingStoreService;
import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessingException;
import org.jboss.as.server.deployment.DeploymentUnitProcessor;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.value.ImmediateValue;

import static org.jboss.as.ee.component.Attachments.EE_MODULE_DESCRIPTION;
import static org.jboss.as.ee.naming.Attachments.MODULE_CONTEXT_CONFIG;
import static org.jboss.as.server.deployment.Attachments.SETUP_ACTIONS;

/**
 * Deployment processor that deploys a naming context for the current module.
 *
 * @author John E. Bailey
 * @author Eduardo Martins
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
public class ModuleContextProcessor implements DeploymentUnitProcessor {

    /**
     * Add a ContextService for this module.
     *
     * @param phaseContext the deployment unit context
     * @throws org.jboss.as.server.deployment.DeploymentUnitProcessingException
     */
    @Override
    public void deploy(DeploymentPhaseContext phaseContext) throws DeploymentUnitProcessingException {
        final DeploymentUnit deploymentUnit = phaseContext.getDeploymentUnit();
        if (DeploymentTypeMarker.isType(DeploymentType.EAR, deploymentUnit)) {
            return;
        }
        EEModuleDescription moduleDescription = deploymentUnit.getAttachment(EE_MODULE_DESCRIPTION);
        final ServiceTarget serviceTarget = phaseContext.getServiceTarget();

        final ServiceName appContextServiceName = ContextNames.contextServiceNameOfApplication(moduleDescription.getApplicationName());
        final ServiceName moduleContextServiceName = ContextNames.contextServiceNameOfModule(moduleDescription.getApplicationName(), moduleDescription.getModuleName());
        final NamingStoreService contextService = new NamingStoreService(true);
        serviceTarget.addService(moduleContextServiceName, contextService).install();

        final ServiceName moduleNameServiceName = moduleContextServiceName.append("ModuleName");
        final BinderService moduleNameBinder = new BinderService("ModuleName");
        moduleNameBinder.getManagedObjectInjector().inject(new ValueManagedReferenceFactory(new ImmediateValue(moduleDescription.getModuleName())));
        serviceTarget.addService(moduleNameServiceName, moduleNameBinder)
                .addDependency(moduleContextServiceName, ServiceBasedNamingStore.class, moduleNameBinder.getNamingStoreInjector())
                .install();
        deploymentUnit.addToAttachmentList(org.jboss.as.server.deployment.Attachments.JNDI_DEPENDENCIES, moduleNameServiceName);

        deploymentUnit.putAttachment(MODULE_CONTEXT_CONFIG, moduleContextServiceName);

        final InjectedEENamespaceContextSelector selector = new InjectedEENamespaceContextSelector();
        phaseContext.requires(appContextServiceName, selector.getAppContextSupplier());
        phaseContext.requires(moduleContextServiceName, selector.getModuleContextSupplier());
        phaseContext.requires(moduleContextServiceName, selector.getCompContextSupplier());
        phaseContext.requires(ContextNames.JBOSS_CONTEXT_SERVICE_NAME, selector.getJbossContextSupplier());
        phaseContext.requires(ContextNames.EXPORTED_CONTEXT_SERVICE_NAME, selector.getExportedContextSupplier());
        phaseContext.requires(ContextNames.GLOBAL_CONTEXT_SERVICE_NAME, selector.getGlobalContextSupplier());

        moduleDescription.setNamespaceContextSelector(selector);

        final Set<ServiceName> serviceNames = new HashSet<ServiceName>();
        serviceNames.add(appContextServiceName);
        serviceNames.add(moduleContextServiceName);
        serviceNames.add(ContextNames.JBOSS_CONTEXT_SERVICE_NAME);
        serviceNames.add(ContextNames.GLOBAL_CONTEXT_SERVICE_NAME);

        // add the arquillian setup action, so the module namespace is available in arquillian tests
        final JavaNamespaceSetup setupAction = new JavaNamespaceSetup(selector, deploymentUnit.getServiceName());
        deploymentUnit.addToAttachmentList(SETUP_ACTIONS, setupAction);
        deploymentUnit.addToAttachmentList(org.jboss.as.ee.component.Attachments.WEB_SETUP_ACTIONS, setupAction);
        deploymentUnit.putAttachment(Attachments.JAVA_NAMESPACE_SETUP_ACTION, setupAction);
    }

    @Override
    public void undeploy(DeploymentUnit deploymentUnit) {
        JavaNamespaceSetup action = deploymentUnit.removeAttachment(Attachments.JAVA_NAMESPACE_SETUP_ACTION);
        if (action != null) {
            deploymentUnit.getAttachmentList(org.jboss.as.ee.component.Attachments.WEB_SETUP_ACTIONS).remove(action);
            deploymentUnit.getAttachmentList(SETUP_ACTIONS).remove(action);
        }
        deploymentUnit.removeAttachment(MODULE_CONTEXT_CONFIG);
    }
}
