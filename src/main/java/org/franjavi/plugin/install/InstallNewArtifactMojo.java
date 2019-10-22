package org.franjavi.plugin.install;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.building.StringModelSource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolver;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolverException;
import org.apache.maven.shared.transfer.project.install.ProjectInstaller;
import org.apache.maven.shared.transfer.project.install.ProjectInstallerRequest;
import org.apache.maven.shared.transfer.repository.RepositoryManager;
import org.apache.maven.shared.utils.Os;
import org.codehaus.plexus.util.FileUtils;

import java.io.File;

/**
 * Installs a file in the local repository if is not found in the remote or local repository
 */
@Mojo(name = "install-file-if-not-exist", defaultPhase = LifecyclePhase.INSTALL)
public class InstallNewArtifactMojo extends AbstractMojo {

    /**
     * GroupId of the artifact to be installed. Retrieved from POM file if one is specified or extracted from
     * {@code pom.xml} in jar if available.
     */
    @Parameter(property = "groupId", required = true)
    private String groupId;

    /**
     * ArtifactId of the artifact to be installed. Retrieved from POM file if one is specified or extracted from
     * {@code pom.xml} in jar if available.
     */
    @Parameter(property = "artifactId", required = true)
    private String artifactId;

    /**
     * Version of the artifact to be installed. Retrieved from POM file if one is specified or extracted from
     * {@code pom.xml} in jar if available.
     */
    @Parameter(property = "version", required = true)
    private String version;

    /**
     * Packaging type of the artifact to be installed. Retrieved from POM file if one is specified or extracted from
     * {@code pom.xml} in jar if available.
     */
    @Parameter(property = "packaging")
    private String packaging;

    /**
     * Classifier type of the artifact to be installed. For example, "sources" or "javadoc". Defaults to none which
     * means this is the project's main artifact.
     *
     * @since 2.2
     */
    @Parameter(property = "classifier")
    private String classifier;

    /**
     * The file to be installed in the local repository.
     */
    @Parameter(property = "file", required = true)
    private File file;

    /**
     * Used for attaching the artifacts to install to the project.
     */
    @Component
    private MavenProjectHelper projectHelper;

    /**
     * Used for creating the project to which the artifacts to install will be attached.
     */
    @Component
    private ProjectBuilder projectBuilder;

    /**
     * Used to install the project created.
     */
    @Component
    private ProjectInstaller installer;

    /**
     * Used to access the remote repository
     */
    @Component
    private ArtifactResolver artifactResolver;

    /**
     * Used to access the local repository
     */
    @Component
    private RepositoryManager repositoryManager;

    @Parameter(defaultValue = "${session}", required = true, readonly = true)
    private MavenSession session;

    /**
     * @see org.apache.maven.plugin.Mojo#execute()
     */
    public void execute() throws MojoExecutionException, MojoFailureException {

        if (!file.exists()) {
            String message = "The specified file '" + file.getPath() + "' does not exists";
            getLog().error(message);
            throw new MojoFailureException(message);
        }

        ProjectBuildingRequest buildingRequest = session.getProjectBuildingRequest();

        if (packaging == null) {
            packaging = FileUtils.getExtension(file.getName());
        }

        MavenProject project = createMavenProject();

        // Create the artifact
        DefaultArtifactHandler ah = new DefaultArtifactHandler(packaging);
        ah.setExtension(FileUtils.getExtension(file.getName()));
        project.getArtifact().setArtifactHandler(ah);
        Artifact artifact = project.getArtifact();

        // Sets the classifier and defines if is pom artifact if not
        if (classifier == null) {
            artifact.setFile(file);
            if ("pom".equals(packaging)) {
                project.setFile(file);
            }
        } else {
            projectHelper.attachArtifact(project, packaging, classifier, file);
            artifact = project.getAttachedArtifacts().get(0);
        }

        // Checks if artifact exists. If it does not, is installed
        if (artifactExistsInRepo(buildingRequest, artifact)) {
            getLog().info("The artifact already exists. No need to install it.");
            return;
        } else {
            getLog().info("Artifact not found. Installing it.");
        }

        try {
            ProjectInstallerRequest projectInstallerRequest =
                    new ProjectInstallerRequest().setProject(project);

            installer.install(buildingRequest, projectInstallerRequest);
        } catch (Exception e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    /**
     * Checks if the artifact is in the local or remote repository.
     * Does not throw exceptions if not found in the remote or remote is not accessible
     *
     * @param buildingRequest {@link ProjectBuildingRequest}.
     * @param artifact        The artifact whose local repo path should be determined, must not be <code>null</code>.
     * @return True if the artifact already exists in the repository, false otherwise
     */
    private boolean artifactExistsInRepo(ProjectBuildingRequest buildingRequest, Artifact artifact) {
        getLog().info("Checking if the artifact exists in the repo.");
        // Check local
        File localFile = getLocalRepoFile(buildingRequest, artifact);
        if (localFile.isFile()) {
            getLog().info("The artifact is in the local repo");
            return true;
        } else {
            getLog().info("The artifact is not in the local repo");
        }

        // Check remote
        boolean found = false;
        try {
            artifactResolver.resolveArtifact(buildingRequest, artifact);
            getLog().info("The artifact is in the remote repo");
            found = true;
            // artifactResolver throws ArtifactResolverException when the artifact is not found
        } catch (ArtifactResolverException e) {
            getLog().warn("The artifact could not be found in the remote. " + e.getMessage());
            // The build should not fail even if not accessible
        } catch (Exception e) {
            getLog().warn(e);
        }
        return found;
    }

    /**
     * Creates a Maven project in-memory from the user-supplied groupId, artifactId and version. When a classifier is
     * supplied, the packaging must be POM because the project with only have attachments. This project serves as basis
     * to attach the artifacts to install to.
     *
     * @return The created Maven project, never <code>null</code>.
     * @throws MojoExecutionException When the model of the project could not be built.
     * @throws MojoFailureException   When building the project failed.
     */
    private MavenProject createMavenProject()
            throws MojoExecutionException, MojoFailureException {
        if (groupId == null || artifactId == null || version == null || packaging == null) {
            throw new MojoExecutionException("The artifact information is incomplete: 'groupId', 'artifactId', "
                    + "'version' and 'packaging' are required.");
        }
        ModelSource modelSource = new StringModelSource("<project><modelVersion>4.0.0</modelVersion><groupId>"
                + groupId + "</groupId><artifactId>" + artifactId + "</artifactId><version>" + version
                + "</version><packaging>" + (classifier == null ? packaging : "pom") + "</packaging></project>");
        ProjectBuildingRequest pbr = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
        pbr.setProcessPlugins(false);
        try {
            return projectBuilder.build(modelSource, pbr).getProject();
        } catch (ProjectBuildingException e) {
            if (e.getCause() instanceof ModelBuildingException) {
                throw new MojoExecutionException("The artifact information is not valid:" + Os.LINE_SEP
                        + e.getCause().getMessage());
            }
            throw new MojoFailureException("Unable to create the project.", e);
        }
    }

    /**
     * Gets the path of the specified artifact within the local repository. Note that the returned path need not exist
     * (yet).
     *
     * @param buildingRequest {@link ProjectBuildingRequest}.
     * @param artifact        The artifact whose local repo path should be determined, must not be <code>null</code>.
     * @return The absolute path to the artifact when installed, never <code>null</code>.
     */
    private File getLocalRepoFile(ProjectBuildingRequest buildingRequest, Artifact artifact) {
        String path = repositoryManager.getPathForLocalArtifact(buildingRequest, artifact);
        return new File(repositoryManager.getLocalRepositoryBasedir(buildingRequest), path);
    }
}