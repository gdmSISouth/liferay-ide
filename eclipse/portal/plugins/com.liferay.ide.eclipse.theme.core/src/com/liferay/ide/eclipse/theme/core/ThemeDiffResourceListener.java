/*******************************************************************************
 * Copyright (c) 2000-2010 Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 *******************************************************************************/

package com.liferay.ide.eclipse.theme.core;

import com.liferay.ide.eclipse.core.ILiferayConstants;
import com.liferay.ide.eclipse.core.util.CoreUtil;
import com.liferay.ide.eclipse.project.core.util.ProjectUtil;
import com.liferay.ide.eclipse.sdk.ISDKConstants;
import com.liferay.ide.eclipse.sdk.SDK;
import com.liferay.ide.eclipse.server.core.IPortalConstants;
import com.liferay.ide.eclipse.server.core.IPortalRuntime;
import com.liferay.ide.eclipse.server.util.ServerUtil;
import com.liferay.ide.eclipse.theme.core.facet.ThemePluginFacetInstall;
import com.liferay.ide.eclipse.theme.core.operation.ThemeDescriptorHelper;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.wst.server.core.IRuntime;
import org.osgi.service.prefs.BackingStoreException;

/**
 * @author Greg Amerson
 */
public class ThemeDiffResourceListener implements IResourceChangeListener {

	public void resourceChanged(IResourceChangeEvent event) {
		if (event == null) {			
			return;			
		}

		if (shouldProcessResourceChangedEvent(event)) {
			IResourceDelta delta = event.getDelta();

			try {
				delta.accept(new IResourceDeltaVisitor() {

					public boolean visit(IResourceDelta delta)
						throws CoreException {

						if (shouldProcessResourceDelta(delta)) {
							processResourceChanged(delta);

							return false;
						}
						
						return true;
					}
				});
			}
			catch (CoreException e) {

			}
		}



	}

	private IFile getWorkspaceFile(IPath path) {
		IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
		
		return root.getFile(path);		
	}

	protected boolean isLiferayPluginProject(IPath deltaPath) {
		IFile pluginPackagePropertiesFile = getWorkspaceFile(deltaPath);
		
		if (pluginPackagePropertiesFile != null && pluginPackagePropertiesFile.exists()) {
			return ProjectUtil.isThemeProject(pluginPackagePropertiesFile.getProject());
		}		
		
		return false;		
	}

	protected void processResourceChanged(final IResourceDelta delta)
		throws CoreException {
		
		new WorkspaceJob("Compiling theme") {

			@Override
			public IStatus runInWorkspace(IProgressMonitor monitor)
				throws CoreException {

				SDK sdk = null;
				IProject project = delta.getResource().getProject();

				try {
					sdk = ProjectUtil.getSDK(project, ThemePluginFacetInstall.LIFERAY_THEME_PLUGIN_FACET);
				}
				catch (BackingStoreException e) {
					throw new CoreException(ThemeCore.createErrorStatus(e));
				}

				if (sdk == null) {
					throw new CoreException(
						ThemeCore.createErrorStatus("No SDK for project configured. Could not deploy theme module"));
				}

				IRuntime runtime = ServerUtil.getRuntime(project);

				String appServerDir = runtime.getLocation().toOSString();
				Map<String, String> properties = new HashMap<String, String>();
				properties.put("app.server.type", "tomcat");
				properties.put("app.server.dir", appServerDir);
				properties.put("app.server.deploy.dir", appServerDir + "/webapps");
				properties.put("app.server.lib.global.dir", appServerDir + "/lib/ext");
				properties.put("app.server.portal.dir", appServerDir + "/webapps/ROOT");

				IStatus status = sdk.compileThemePlugin(project, properties);

				if (!status.isOK()) {
					throw new CoreException(status);
				}

				IFolder docroot = ProjectUtil.getDocroot(project);

				IFile lookAndFeelFile = docroot.getFile("WEB-INF/" + IPortalConstants.LIFERAY_LOOK_AND_FEEL_XML_FILE);

				if (!lookAndFeelFile.exists()) {
					String id = project.getName().replaceAll(ISDKConstants.THEME_PLUGIN_PROJECT_SUFFIX, "");
					IFile propsFile =
						docroot.getFile("WEB-INF/" + ILiferayConstants.LIFERAY_PLUGIN_PACKAGE_PROPERTIES_FILE);
					String name = id;
					if (propsFile.exists()) {
						Properties props = new Properties();
						try {
							props.load(propsFile.getContents());
							String nameValue = props.getProperty("name");
							if (!CoreUtil.isNullOrEmpty(nameValue)) {
								name = nameValue;
							}
						}
						catch (IOException e) {
							ThemeCore.logError("Unable to load plugin package properties.", e);
						}
					}

					IPortalRuntime portalRuntime = ServerUtil.getPortalRuntime(runtime);
					if (portalRuntime != null) {
						ThemeDescriptorHelper.createDefaultFile(
							lookAndFeelFile, portalRuntime.getVersion() + "+", id, name);
					}
				}

				if (docroot != null && docroot.exists()) {
					docroot.refreshLocal(IResource.DEPTH_INFINITE, null);
				}

				return Status.OK_STATUS;
			}
		}.schedule();
	}

	protected boolean shouldProcessResourceChangedEvent(IResourceChangeEvent event) {
		if (event == null) {
			return false;
		}

		IResourceDelta delta = event.getDelta();
		
		int deltaKind = delta.getKind();
		
		if (deltaKind == IResourceDelta.REMOVED || deltaKind == IResourceDelta.REMOVED_PHANTOM) {
			return false;
		}

		return true;
	}

	protected boolean shouldProcessResourceDelta(IResourceDelta delta) {
		IPath fullPath = delta.getFullPath();
		
		IFolder docroot = ProjectUtil.getDocroot(delta.getResource().getProject());

		if (docroot == null) {
			return false;
		}

		IPath diffPath = docroot.getFolder("_diffs").getFullPath();

		return diffPath.isPrefixOf(fullPath);
	}

}