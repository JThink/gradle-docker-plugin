/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.bmuschko.gradle.docker

import org.gradle.api.GradleException
import org.gradle.testkit.runner.BuildResult

class DockerWorkflowFunctionalTest extends AbstractFunctionalTest {
    def "Can create Dockerfile and build an image from it"() {
        buildFile << """
            import com.bmuschko.gradle.docker.tasks.image.Dockerfile
            import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage
            import com.bmuschko.gradle.docker.tasks.image.DockerInspectImage

            task createDockerfile(type: Dockerfile) {
                destFile = project.file('build/mydockerfile/Dockerfile')
                from '$TEST_IMAGE_WITH_TAG'
                maintainer 'Benjamin Muschko "benjamin.muschko@gmail.com"'
            }

            task buildImage(type: DockerBuildImage) {
                dependsOn createDockerfile
                inputDir = createDockerfile.destFile.parentFile
                tag = "${createUniqueImageId()}"
            }

            task inspectImage(type: DockerInspectImage) {
                dependsOn buildImage
                targetImageId { buildImage.getImageId() }
            }

            task workflow {
                dependsOn inspectImage
            }
        """

        when:
        BuildResult result = build('workflow')

        then:
        new File(projectDir, 'build/mydockerfile/Dockerfile').exists()
        result.output.contains('Author           : Benjamin Muschko "benjamin.muschko@gmail.com"')
    }

    def "Can build and verify image"() {
        File imageDir = temporaryFolder.newFolder('images', 'minimal')
        createDockerfile(imageDir)

        buildFile << """
            import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage
            import com.bmuschko.gradle.docker.tasks.image.DockerInspectImage

            task buildImage(type: DockerBuildImage) {
                inputDir = file('images/minimal')
                tag = "${createUniqueImageId()}"
            }

            task inspectImage(type: DockerInspectImage) {
                dependsOn buildImage
                targetImageId { buildImage.getImageId() }
            }

            task workflow {
                dependsOn inspectImage
            }
        """

        when:
        BuildResult result = build('workflow')

        then:
        result.output.contains('Author           : Benjamin Muschko "benjamin.muschko@gmail.com"')
    }

    def "Can build an image, create and start a container"() {
        File imageDir = temporaryFolder.newFolder('images', 'minimal')
        createDockerfile(imageDir)

        String uniqueContainerName = createUniqueContainerName()

        buildFile << """
            import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage
            import com.bmuschko.gradle.docker.tasks.container.DockerCreateContainer
            import com.bmuschko.gradle.docker.tasks.container.DockerStartContainer
            import com.bmuschko.gradle.docker.tasks.container.DockerInspectContainer
            import com.bmuschko.gradle.docker.tasks.container.DockerRemoveContainer

            task buildImage(type: DockerBuildImage) {
                inputDir = file('images/minimal')
                tag = "${createUniqueImageId()}"
            }

            task createContainer(type: DockerCreateContainer) {
                dependsOn buildImage
                targetImageId { buildImage.getImageId() }
                containerName = "$uniqueContainerName"
                portBindings = ['8080:8080']
                cmd = ['/bin/sh']
            }

            task startContainer(type: DockerStartContainer) {
                dependsOn createContainer
                targetContainerId { createContainer.getContainerId() }
            }

            task inspectContainer(type: DockerInspectContainer) {
                dependsOn startContainer
                targetContainerId { startContainer.getContainerId() }
            }

            task removeContainer(type: DockerRemoveContainer) {
                dependsOn inspectContainer
                removeVolumes = true
                force = true
                targetContainerId { "$uniqueContainerName" }
            }

            task workflow {
                dependsOn removeContainer
            }
        """

        expect:
        BuildResult result = build('workflow')
        result.output.contains("Name        : /$uniqueContainerName")
    }

    def "Can build an image, create and link a container"() {
        File imageDir = temporaryFolder.newFolder('images', 'minimal')
        createDockerfile(imageDir)

        String uniqueContainerName = createUniqueContainerName()

        buildFile << """
            import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage
            import com.bmuschko.gradle.docker.tasks.container.DockerCreateContainer
            import com.bmuschko.gradle.docker.tasks.container.DockerInspectContainer

            task buildImage(type: DockerBuildImage) {
                inputDir = file('images/minimal')
                tag = "${createUniqueImageId()}"
            }

            task createContainer1(type: DockerCreateContainer) {
                dependsOn buildImage
                targetImageId { buildImage.getImageId() }
                containerName = "${uniqueContainerName}1"
                cmd = ['/bin/sh']
            }

            task createContainer2(type: DockerCreateContainer) {
                dependsOn createContainer1
                targetImageId { buildImage.getImageId() }
                containerName = "${uniqueContainerName}2"
                links = ["${uniqueContainerName}1:container1"]
                cmd = ['/bin/sh']
            }

            task inspectContainer(type: DockerInspectContainer) {
                dependsOn createContainer2
                targetContainerId { createContainer2.getContainerId() }
            }

            task workflow {
                dependsOn inspectContainer
            }
        """

        expect:
        BuildResult result = build('workflow')
        result.output.contains("Links       : [${uniqueContainerName}1:container1]")
    }

    def "Can build an image, create a container and link its volumes into another container"() {
        File imageDir = temporaryFolder.newFolder('images', 'minimal')
        File dockerFile = createDockerfile(imageDir)

        dockerFile << 'VOLUME /data'

        String uniqueContainerName = createUniqueContainerName()

        buildFile << """
            import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage
            import com.bmuschko.gradle.docker.tasks.container.DockerCreateContainer
            import com.bmuschko.gradle.docker.tasks.container.DockerInspectContainer

            task buildImage(type: DockerBuildImage) {
                inputDir = file('images/minimal')
                tag = "${createUniqueImageId()}"
            }

            task createContainer1(type: DockerCreateContainer) {
                dependsOn buildImage
                targetImageId { buildImage.getImageId() }
                containerName = "${uniqueContainerName}-1"
                cmd = ['/bin/sh']
            }

            task createContainer2(type: DockerCreateContainer) {
                dependsOn createContainer1
                targetImageId { buildImage.getImageId() }
                containerName = "${uniqueContainerName}-2"
                volumesFrom = ["${uniqueContainerName}-1"]
                cmd = ['/bin/sh']
            }

            task inspectContainer(type: DockerInspectContainer) {
                dependsOn createContainer2
                targetContainerId { createContainer2.getContainerId() }
            }

            task workflow {
                dependsOn inspectContainer
            }
        """

        expect:
        BuildResult result = build('workflow')
        result.output.contains("VolumesFrom : [${uniqueContainerName}-1:rw]")
    }

    def "Can build an image and push to private registry"() {
        File dockerFileLocation = new File(getProjectDir(), 'build/private-reg/Dockerfile')
        if (!dockerFileLocation.parentFile.exists() && !dockerFileLocation.parentFile.mkdirs())
            throw new GradleException("Could not successfully create dockerFileLocation @ ${dockerFileLocation.path}")

        buildFile << """
            import com.bmuschko.gradle.docker.tasks.image.Dockerfile
            import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage
            import com.bmuschko.gradle.docker.tasks.image.DockerPushImage

            task createDockerfile(type: Dockerfile) {
                destFile = project.file("${dockerFileLocation.path}")
                from '$TEST_IMAGE_WITH_TAG'
                maintainer 'Benjamin Muschko "benjamin.muschko@gmail.com"'
                doLast {
                    if (new File("${dockerFileLocation.path}").exists()) {
                        println "Dockerfile does indeed exist."
                    }
                }
            }

            task buildImage(type: DockerBuildImage) {
                dependsOn createDockerfile
                inputDir = createDockerfile.destFile.parentFile
                tag = '${TestConfiguration.dockerPrivateRegistryDomain}/${createUniqueImageId()}'
            }

            task pushImage(type: DockerPushImage) {
                dependsOn buildImage
                conventionMapping.imageName = { buildImage.getTag() }
            }

            task workflow {
                dependsOn pushImage
            }
        """

        expect:
        BuildResult result = build('workflow')
        result.output.contains("Dockerfile does indeed exist.")
    }

    def "Can build an image, create a container, and copy file from it"() {
        File imageDir = temporaryFolder.newFolder('images', 'minimal')
        createDockerfile(imageDir)

        String uniqueContainerName = createUniqueContainerName()

        // required for task `copyFileFromContainerToHostDir`
        File hostPathDir = new File(getProjectDir(), "copy-file-host-dir")
        if (!hostPathDir.mkdirs())
            throw new GradleException("Could not successfully create hostPathDir @ ${hostPathDir.path}")

        buildFile << """
            import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage
            import com.bmuschko.gradle.docker.tasks.container.DockerCreateContainer
            import com.bmuschko.gradle.docker.tasks.container.DockerCopyFileFromContainer

            task buildImage(type: DockerBuildImage) {
                inputDir = file('images/minimal')
                tag = "${createUniqueImageId()}"
            }

            task createContainer(type: DockerCreateContainer) {
                dependsOn buildImage
                targetImageId { buildImage.getImageId() }
                containerName = "$uniqueContainerName"
                cmd = ['/bin/sh']
            }

            task copyFileFromContainerToHostFile(type: DockerCopyFileFromContainer) {
                dependsOn createContainer
                targetContainerId { createContainer.getContainerId() }
                hostPath = "$projectDir/copy-file-host-file/shebang.tar"
                remotePath = "/bin/sh"
                compressed = true
            }

            task copyFileFromContainerToHostDir(type: DockerCopyFileFromContainer) {
                dependsOn copyFileFromContainerToHostFile
                targetContainerId { createContainer.getContainerId() }
                hostPath = "$projectDir/copy-file-host-dir"
                remotePath = "/bin/sh"
            }

            task copyDirFromContainerToHostDir(type: DockerCopyFileFromContainer) {
                dependsOn copyFileFromContainerToHostDir
                targetContainerId { createContainer.getContainerId() }
                hostPath = "$projectDir/copy-dir"
                remotePath = "/var/spool"
            }

            task workflow {
                dependsOn copyDirFromContainerToHostDir
            }
        """

        when:
        build('workflow')

        then:
        new File("$projectDir/copy-file-host-file/shebang.tar").exists()
        new File("$projectDir/copy-file-host-dir/sh").exists()
        new File("$projectDir/copy-dir").exists()
    }

    def "Can build an image, create a container and expose a port"() {
        File imageDir = temporaryFolder.newFolder('images', 'minimal')
        File dockerFile = createDockerfile(imageDir)

        String uniqueContainerName = createUniqueContainerName()

        buildFile << """
            import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage
            import com.bmuschko.gradle.docker.tasks.container.DockerCreateContainer
            import com.bmuschko.gradle.docker.tasks.container.DockerInspectContainer

            task buildImage(type: DockerBuildImage) {
                inputDir = file("${dockerFile.parentFile.path}")
                tag = "${createUniqueImageId()}"
            }

            task createContainer(type: DockerCreateContainer) {
                dependsOn buildImage
                targetImageId { buildImage.getImageId() }
                containerName = "${uniqueContainerName}"
                exposePorts("tcp", [9999])
                cmd = ['/bin/sh']
            }

            task inspectContainer(type: DockerInspectContainer) {
                dependsOn createContainer
                targetContainerId { createContainer.getContainerId() }
            }

            task workflow {
                dependsOn inspectContainer
            }
        """

        expect:
        BuildResult result = build('workflow')
        result.output.contains("ExposedPorts : [9999/tcp]")
    }

    def "Can build an image, create a container and set LogConfig"() {
        File imageDir = temporaryFolder.newFolder('images', 'minimal')
        File dockerFile = createDockerfile(imageDir)

        String uniqueContainerName = createUniqueContainerName()

        buildFile << """
            import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage
            import com.bmuschko.gradle.docker.tasks.container.DockerCreateContainer
            import com.bmuschko.gradle.docker.tasks.container.DockerInspectContainer

            task buildImage(type: DockerBuildImage) {
                inputDir = file("${dockerFile.parentFile.path}")
                tag = "${createUniqueImageId()}"
            }

            task createContainer(type: DockerCreateContainer) {
                dependsOn buildImage
                targetImageId { buildImage.getImageId() }
                containerName = "${uniqueContainerName}"
                logConfig("none", [:])
                cmd = ['/bin/sh']
            }

            task inspectContainer(type: DockerInspectContainer) {
                dependsOn createContainer
                targetContainerId { createContainer.getContainerId() }
            }

            task workflow {
                dependsOn inspectContainer
            }
        """

        expect:
        BuildResult result = build('workflow')
        result.output.contains("LogConfig : none")
    }

    def "Can build an image, create a container and set RestartPolicy"() {
        File imageDir = temporaryFolder.newFolder('images', 'minimal')
        File dockerFile = createDockerfile(imageDir)

        String uniqueContainerName = createUniqueContainerName()

        buildFile << """
            import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage
            import com.bmuschko.gradle.docker.tasks.container.DockerCreateContainer
            import com.bmuschko.gradle.docker.tasks.container.DockerInspectContainer

            task buildImage(type: DockerBuildImage) {
                inputDir = file("${dockerFile.parentFile.path}")
                tag = "${createUniqueImageId()}"
            }

            task createContainer(type: DockerCreateContainer) {
                dependsOn buildImage
                targetImageId { buildImage.getImageId() }
                containerName = "${uniqueContainerName}"
                restartPolicy("on-failure", 999)
                cmd = ['/bin/sh']
            }

            task inspectContainer(type: DockerInspectContainer) {
                dependsOn createContainer
                targetContainerId { createContainer.getContainerId() }
            }

            task workflow {
                dependsOn inspectContainer
            }
        """

        expect:
        BuildResult result = build('workflow')
        result.output.contains("RestartPolicy : on-failure:999")
    }

    def "Can build an image, create a container and set devices"() {
        File imageDir = temporaryFolder.newFolder('images', 'minimal')
        File dockerFile = createDockerfile(imageDir)

        String uniqueContainerName = createUniqueContainerName()

        buildFile << """
            import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage
            import com.bmuschko.gradle.docker.tasks.container.DockerCreateContainer
            import com.bmuschko.gradle.docker.tasks.container.DockerInspectContainer

            task buildImage(type: DockerBuildImage) {
                inputDir = file("${dockerFile.parentFile.path}")
                tag = "${createUniqueImageId()}"
            }

            task createContainer(type: DockerCreateContainer) {
                dependsOn buildImage
                targetImageId { buildImage.getImageId() }
                containerName = "${uniqueContainerName}"
                devices = ["/dev/sda:/dev/xvda:rwm"]
                cmd = ['/bin/sh']
            }

            task inspectContainer(type: DockerInspectContainer) {
                dependsOn createContainer
                targetContainerId { createContainer.getContainerId() }
            }

            task workflow {
                dependsOn inspectContainer
            }
        """

        expect:
        BuildResult result = build('workflow')
        result.output.contains("Devices : [/dev/sda:/dev/xvda:rwm]")
    }

    def "Can build an image, create a container and assign a network and alias"() {
        File imageDir = temporaryFolder.newFolder('images', 'minimal')
        File dockerFile = createDockerfile(imageDir)

        String uniqueContainerName = createUniqueContainerName()
        String uniqueNetworkName = createUniqueNetworkName()

        buildFile << """
            import com.bmuschko.gradle.docker.tasks.AbstractDockerRemoteApiTask
            import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage
            import com.bmuschko.gradle.docker.tasks.container.DockerCreateContainer
            import com.bmuschko.gradle.docker.tasks.container.DockerInspectContainer

            class DockerCreateNetworkTask extends AbstractDockerRemoteApiTask {
                @Override
                void runRemoteCommand(dockerClient) {
                    dockerClient.createNetworkCmd().withName("${uniqueNetworkName}").exec()
                }
            }
            class DockerRemoveNetworkTask extends AbstractDockerRemoteApiTask {
                @Override
                void runRemoteCommand(dockerClient) {
                    dockerClient.removeNetworkCmd("${uniqueNetworkName}").exec()
                }
            }

            task removeNetwork(type: DockerRemoveNetworkTask)
            task createNetwork(type: DockerCreateNetworkTask)

            task buildImage(type: DockerBuildImage) {
                inputDir = file("${dockerFile.parentFile.path}")
                tag = "${createUniqueImageId()}"
            }

            task createContainer(type: DockerCreateContainer) {
                dependsOn buildImage, createNetwork
                targetImageId { buildImage.getImageId() }
                containerName = "${uniqueContainerName}"
                networkMode = "${uniqueNetworkName}"
                networkAliases = ["some-alias"]
                cmd = ['/bin/sh']
            }

            task inspectContainer(type: DockerInspectContainer) {
                dependsOn createContainer
                targetContainerId { createContainer.getContainerId() }
                onNext { container ->
                    println container.networkSettings.networks["${uniqueNetworkName}"].aliases
                }
            }

            task workflow {
                dependsOn inspectContainer
            }

            removeNetwork.mustRunAfter workflow
            createNetwork.finalizedBy removeNetwork
        """

        expect:
        BuildResult result = build('workflow')
        result.output.contains('[some-alias]')
    }

    private File createDockerfile(File imageDir) {
        File dockerFile = new File(imageDir, 'Dockerfile')
        dockerFile << """
FROM $TEST_IMAGE_WITH_TAG
MAINTAINER Benjamin Muschko "benjamin.muschko@gmail.com"
"""
        dockerFile
    }
}
