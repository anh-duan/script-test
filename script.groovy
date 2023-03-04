def call(Map config = [:]) {

  //TODO: input validation
  
  pipeline {
    agent {
      kubernetes {
        defaultContainer 'eod-tools'
        inheritFrom "tdh-eod-primary-pipeline-ucp-service-nodes"
        yaml libraryResource(".jenkins-${params.DATACENTER}-pipeline-agents-eod-pod.yaml")
        showRawYaml false
      }
    }

    options {
      buildDiscarder(logRotator(daysToKeepStr: '14'))
      timeout(time: 3, unit: 'HOURS')
      skipStagesAfterUnstable()
    }

    environment {

      SERVICE_NAME = "${config.serviceName}"
      ENV_FILENAME = "${config.envFileName}"

      CLUSTER_DOMAIN = "${params.PLATFORM}.${params.DOMAIN.trim()}"
      DOMAIN = "${params.PLATFORM.equalsIgnoreCase('tdock') ? params.DOMAIN.trim() : env.CLUSTER_DOMAIN}"
      PRIVATE_CLUSTER_DOMAIN = "${params.PLATFORM}.${params.PRIVATE_DOMAIN.trim()}"
      PRIVATE_DOMAIN = "${params.PLATFORM.equalsIgnoreCase('tdock') ? params.PRIVATE_DOMAIN.trim() : env.PRIVATE_CLUSTER_DOMAIN}"
      AWS_ACCOUNT = "${params.PLATFORM.equalsIgnoreCase('tdh-prod') ? '811260876530' : '601303390846'}"
      REPO = "${GIT_URL.tokenize('/.')[-2]}"
      COMMIT = "${GIT_COMMIT.substring(0, Math.min(GIT_COMMIT.length(), 10))}"
      NAMESPACE = "ucp-sit"
      MEDULLA_SUBDIR = "medulla@${env.BUILD_NUMBER}"
      MEDULA_CREDENTIALS_ID="95fa3603-0ab6-4a28-830e-916b4809ff33"
      VERSION = '4.0' // must be greater-than-or-equal-to upstream job

      // AWS settings
      //AWS_CREDENTIALS_ID = 'jenkins-devops-terraform'
      AWS_CREDENTIALS_ID = 'aws-iwa-staging-eks-deploy-user'
      EKS_CLUSTER_NAME = "staging-eks-cluster"

      // Azure settings
      AZURE_TENANT_ID = getEnvParam.AZURE_TENANT_ID(env.PLATFORM)
      AZURE_CONFIG_DIR = "/home/jenkins/agent/workspace/${env.JOB_NAME}@${env.BUILD_NUMBER}/.azure"
      AZURE_CREDENTIALS_ID = "azure-${env.PLATFORM}-env"

      IMAGE_NAME = "${env.SERVICE_NAME}-service"
      RUNTIME_BUILD_TAG="runtime"
      RUNTIME_BUILD_PARAMS="--build-arg BUILD_RUNTIME=1"
      BUILD_CONTEXT="."
      BUILD_TAG = "commit-${COMMIT}"
      DEPLOY_TAG = "deploy-${params.PLATFORM}-${params.DATACENTER}-${params.CLUSTER}-${NAMESPACE}-${COMMIT}"
      ARTIFACTORY_PREFIX = "ucp"
      DOCKER_REGISTRY_CREDENTIALS_ID = "artifactory-svc-docker-promoter"
      DOCKER_REGISTRY_DOMAIN = "artifactory.intouchhealth.io"
      DOCKER_REGISTRY_TYPE = "docker-snapshot-local"
      DOCKER_REGISTRY = "docker.${env.DOCKER_REGISTRY_DOMAIN}/${env.ARTIFACTORY_PREFIX}" // the virtual docker image registry used for pulling images
      DOCKER_REGISTRY_API_URL = "${env.DOCKER_REGISTRY_DOMAIN}/artifactory/api/docker/${env.DOCKER_REGISTRY_TYPE}/v2/${env.ARTIFACTORY_PREFIX}"

      SONARQUBE_URL = ""
      SONARQUBE_AUTH_TOKEN = ""
    }

    parameters {
      choice(choices: 'aws\nazure', description: 'Data Center', name: 'DATACENTER')
      string(defaultValue: 'staging', description: 'Cluster name (e.g. dev, qa, uat)', name: 'CLUSTER')
      string(defaultValue: 'overlai', description: 'Platform Name(e.g. overlai, tdock)', name: 'PLATFORM')
      string(defaultValue: 'teladoc.io', description: 'Domain', name: 'DOMAIN')
      string(defaultValue: '', description: 'Private domain', name: 'PRIVATE_DOMAIN')
      string(defaultValue: 'USA', description: 'Country for Application Stack (e.g USA, CAN, EU)', name: 'LOCATION')
      choice(choices: 'us-east-1', description: 'AWS Region', name: 'AWS_REGION')
      //string(defaultValue: '', description: 'Namespace Target (e.g. lowercase branch)', name: 'NAMESPACE')
      choice(choices: 'ucp-sit', description: 'Namespace Target', name: 'NAMESPACE')
      //choice(choices: 'ephemeral\npersistent', description: 'Environment type', name: 'CLASS')
      choice(choices: 'public\nprivate', description: 'Environment visibility.\nprivate - environment will be accessible only from whitelisted locations.\npublic - environment will be accessible from', name: 'VISIBILITY')

      booleanParam(description: 'If true, should run publish and deploy', name: 'WITH_DEPLOY', defaultValue: false)
    }


    stages {
      stage('Start') {
        when { expression { config.stagesList.contains("start") } }
        steps {
          script {
            echo "${COMMIT}"
            echo "ucp configs: ${config}"
            env.DOCKER_IMAGE_EXISTS = dockerImageExists(DOCKER_REGISTRY_API_URL, DOCKER_REGISTRY_CREDENTIALS_ID, IMAGE_NAME, BUILD_TAG)
          }
        }
      }

      stage('Validate Docker image'){
        when { expression { config.stagesList.contains("validateImage") } }
        steps {
          container('hadolint') {
            script {
              dockerImage.validate(WORKSPACE)
            }
          }
        }
      }

      stage('Runtime env') {
        steps {
          container("docker") {
            script {
              cleanupRuntime()
              sh "docker network create ${SERVICE_NAME}-runtime-network || true"
              sh "docker volume create ${SERVICE_NAME}-runtime"
              sh """
                docker run --rm --name ${SERVICE_NAME}-runtime-db \
                  --env-file ${ENV_FILENAME} \
                  --network=${SERVICE_NAME}-runtime-network \
                  -d \
                  postgres:14.4-alpine
              """
              sh """
                docker run --rm --name ${SERVICE_NAME}-runtime-maildev \
                  --env-file ${ENV_FILENAME} \
                  --network=${SERVICE_NAME}-runtime-network \
                  -d \
                  djfarrelly/maildev
              """
              dockerImage.build(DOCKER_REGISTRY_TYPE, DOCKER_REGISTRY_DOMAIN, ARTIFACTORY_PREFIX, DOCKER_REGISTRY_CREDENTIALS_ID, IMAGE_NAME, RUNTIME_BUILD_TAG, RUNTIME_BUILD_PARAMS, BUILD_CONTEXT)
            }
          }
        }
      }

      stage("Build & Check") {
        parallel {
          stage('Build image') {
            steps {
              container("docker") {
                script {
                  dockerImage.build(DOCKER_REGISTRY_TYPE, DOCKER_REGISTRY_DOMAIN, ARTIFACTORY_PREFIX, DOCKER_REGISTRY_CREDENTIALS_ID, IMAGE_NAME, BUILD_TAG)
                }
              }
            }
          }

          stage('Code Quality verification') {
            when { expression { config.stagesList.contains("codeQuality") } }
            steps {
              container("docker") {
                script {
                  runInContainer("gradle spotbugsMain pmdMain", "quality_scanner")
                }
              }
            }
            post {
              always {
                archiveContainerArtifacts("quality_scanner", "/app/build/reports/pmd/.", "pmd")
                archiveContainerArtifacts("quality_scanner", "/app/build/reports/spotbugs/.", "spotbugs")
                container("docker") {
                  sh "docker rm -f  quality_scanner || true"
                }
              }
            }
          }

          stage('Code Style verification') {
            when { expression { config.stagesList.contains("codeStyle") } }
            steps {
              container("docker") {
                script {
                  runInContainer("gradle checkstyleMain checkstyleTest", "codestyle_scanner")
                }
              }
            }
            post {
              always {
                archiveContainerArtifacts("codestyle_scanner", "/app/build/reports/checkstyle/main.html", "checkstyle-main")
                archiveContainerArtifacts("codestyle_scanner", "/app/build/reports/checkstyle/test.html", "checkstyle-test")
                container("docker") {
                  sh "docker rm -f  codestyle_scanner || true"
                }
              }
            }
          }

          stage('Tests') {
            when { expression { config.stagesList.contains("codeTest") } }
            steps {
              container("docker") {
                script {
                  runInContainer("gradle test jacocoTestReport", "junit")
                }
              }
            }
            post {
              always {
                copyFromContainer("junit", "/app/build/reports/tests/.", "artifacts/junit")
                junit "artifacts/junit/*.xml"
                archiveContainerArtifacts("junit", "/app/artifacts/junit/jacoco/test/html/.", "jacoco")
                container("docker") {
                  sh "rm -rf artifacts/junit"
                  sh "docker rm -f  junit || true"
                }
              }
            }
          }

          stage('CheckMarx security scan') {
            when { expression { config.stagesList.contains("checkMarxScan") } }
            steps {
              script {
                ithCheckmarx.scanProject(projectname: "devopsCloudbees", excludefiles: "/")
              }
            }
            post {
              always {
                dir("Checkmarx") {
                  sh "tar -czf CheckmarxReport.tar.gz Reports"
                  archiveArtifacts artifacts: "CheckmarxReport.tar.gz", allowEmptyArchive: true
                }
              }
            }
          }

          stage('SonarQube scan') {
            when { expression { config.stagesList.contains("sonarQubeScan") } }
            steps {
              container("docker") {
                script {
                  runInContainer("gradle sonarqube -Dsonar.verbose=true -Dsonar.host.url=${SONARQUBE_URL} -Dsonar.login=${SONARQUBE_AUTH_TOKEN}", "sonar_scanner")
                }
              }
            }
          }

          stage('Publish API Documentation') {
            when { expression { config.stagesList.contains("publishDocument") } }
            steps {
              container("docker") {
                script {
                  archiveArtifacts artifacts: "openapi.yaml"
                }
              }
            }
          }
        }
      }

      stage('Publish') {
        steps {
          container("docker") {
            script {
              dockerImage.push(DOCKER_REGISTRY_TYPE, DOCKER_REGISTRY_DOMAIN, ARTIFACTORY_PREFIX, DOCKER_REGISTRY_CREDENTIALS_ID, IMAGE_NAME, BUILD_TAG)
            }
          }
        }
      }
      stage('Promote Artifact to Release Repo') {
        when { expression { config.stagesList.contains("promoteArtifactToRelease") } }
        steps {
          script {
            ithArtifactoryReleaseUpload.docker(
                repo: "${ARTIFACTORY_PREFIX}/${IMAGE_NAME}",
                sourceTag: "${BUILD_TAG}",
                targetTag: "${BUILD_TAG}")
          }
        }
      }
      stage('Tag') {
        steps {
          script {
            dockerImage.tag(DOCKER_REGISTRY_TYPE, DOCKER_REGISTRY_DOMAIN, ARTIFACTORY_PREFIX, DOCKER_REGISTRY_CREDENTIALS_ID, IMAGE_NAME, BUILD_TAG, DEPLOY_TAG)
          }
        }
      }
      stage('Artifact Repo Retention & Cleanup') {
        steps {
          script {
            ithArtifactorySnapshotRetention.basedonversion(
                repositoryname: "${DOCKER_REGISTRY_TYPE}",
                artifactpath: "${ARTIFACTORY_PREFIX}/${IMAGE_NAME}",
                numberOfversiontokeep: '10',
                dryrun: 'true')
          }
        }
      }

      stage('Deploy') {
        when {
          expression { shouldPublish() }
        }
        stages {
          stage('configure kubectl') {
            steps {
              container("eod-tools"){
                script {
                  //cloneMedulla(MEDULLA_SUBDIR)
                  //configureKubectl(MEDULLA_SUBDIR)
                  cloneIWAMedulla(MEDULLA_SUBDIR, MEDULA_CREDENTIALS_ID)
                  configureIWAKubectl()
                }
              }
            }
          }
          stage('kubectl apply pgsql') {
            when { expression { config.stagesList.contains("addPostgresDeploy") } }
            steps {
              container("eod-tools"){
                deployHelmChart(AWS_CREDENTIALS_ID, MEDULLA_SUBDIR, AWS_REGION, '../config/deploy/postgresql')
              }
            }
            post {
              always {
                script {
                  sh "mv config/manifest.yml manifest-pgsql.yml"
                  archiveArtifacts artifacts: 'manifest-pgsql.yml'
                }
              }
            }
          }
          stage('kubectl apply kafka') {
            when { expression { config.stagesList.contains("addKafkaDeploy") } }
            steps {
              container("eod-tools"){
                deployHelmChart(AWS_CREDENTIALS_ID, MEDULLA_SUBDIR, AWS_REGION, '../config/deploy/kafka')
              }
            }
            post {
              always {
                script {
                  sh "mv config/manifest.yml manifest-kafka.yml"
                  archiveArtifacts artifacts: 'manifest-kafka.yml'
                }
              }
            }
          }
          stage('kubectl apply') {
            when { expression { namespaceExists(env.NAMESPACE) } }
            steps {
              container("eod-tools"){
                deployHelmChart(AWS_CREDENTIALS_ID, MEDULLA_SUBDIR, AWS_REGION, '../config/deploy/service')
              }
            }
            post {
              always {
                script {
                  sh "mv config/manifest.yml manifest-service.yml"
                  archiveArtifacts artifacts: 'manifest-service.yml'
                }
              }
            }
          }
        }
      }
      stage('Done') {
        steps {
          echo "Done"
        }
      }
    }

    post {
      cleanup {
        script {
          echo "Cleanup"
          container("docker") {
            cleanupRuntime()
          }
          deleteDir()
          dir("${workspace}@tmp") {
            deleteDir()
          }
        }
      }
    }
  }
}

def cleanupRuntime() {
  // Clear docker containers and volumes
  sh "docker rm -f ${SERVICE_NAME}-runtime-db || true"
  sh "docker rm -f ${SERVICE_NAME}-runtime-maildev || true"
  sh "docker volume rm ${SERVICE_NAME}-runtime || true"
}

def runInContainer(String command, String containerName) {
  sh "docker rm -f ${containerName} || true"
  sh """
        docker run \
            --name ${containerName} \
            --env-file ${ENV_FILENAME} \
            -e POSTGRES_HOST=${SERVICE_NAME}-runtime-db \
            -e POSTGRES_PORT=5432 \
            -e DB_HOST=${SERVICE_NAME}-runtime-db \
            -e DB_PORT=5432 \
            -e MAILER_HOST=${SERVICE_NAME}-runtime-maildev \
            --network=${SERVICE_NAME}-runtime-network \
            ${DOCKER_REGISTRY_TYPE}.${DOCKER_REGISTRY_DOMAIN}/${ARTIFACTORY_PREFIX}/${IMAGE_NAME}:${RUNTIME_BUILD_TAG} \
            ${command}
        """
}

def copyFromContainer(String containerName, String from, String to) {
  container("docker") {
    sh "mkdir -p ${to}"
    echo "copy from container ${containerName}:${from} to ${to}"
    sh """
      docker cp ${containerName}:${from} ${to} || true
      """
  }
}

def archiveContainerArtifacts(String containerName, String path, String artifactName) {
  container("docker") {
    copyFromContainer(containerName, path, "artifacts/${artifactName}")
    echo "archiving artifacts to ${artifactName}.tgz"
    sh "cd artifacts && tar -zcvf ${artifactName}.tgz ${artifactName}"
    archiveArtifacts artifacts: "artifacts/${artifactName}.tgz", allowEmptyArchive: true
    echo "removing artifact sources artifacts/${artifactName} artifacts/${artifactName}.tgz"
    sh "rm -rf artifacts/${artifactName} artifacts/${artifactName}.tgz"
  }
}

def shouldPublish() {
  return !!params.WITH_DEPLOY
}

def configureIWAKubectl() {
  container("eod-tools") {
    echo "executing setup.sh to configure aws profiles and kubernetes"
    script {
      withCredentials([[
                           $class: 'AmazonWebServicesCredentialsBinding',
                           credentialsId: AWS_CREDENTIALS_ID,
                           accessKeyVariable: 'AWS_ACCESS_KEY_ID', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                       ]]) {
        sh('aws configure set aws_access_key_id $AWS_ACCESS_KEY_ID')
        sh('aws configure set aws_secret_access_key $AWS_SECRET_ACCESS_KEY')
        sh('aws configure set region $AWS_REGION')
        sh('aws eks update-kubeconfig --name $EKS_CLUSTER_NAME')
      }
    }
  }
}

def cloneIWAMedulla(String dir, String medulaCredentialsId) {
  println "using ${medulaCredentialsId} credentials"
  println "determining branch for Medulla"

  def scmResult = resolveScm(
      source: [
          $class: 'GitSCMSource', id: '_', credentialsId: medulaCredentialsId,
          traits: [[$class: 'jenkins.plugins.git.traits.BranchDiscoveryTrait']],
          remote: "https://github.com/Teladoc/medulla"
      ],
      targets: [env.BRANCH_NAME, 'master']
  )
  def branchName = scmResult.getBranches().get(0).getName()

  println "using ${branchName} for Medulla"
  checkout([
      $class: 'GitSCM',
      branches: [[name: "${branchName}"]],
      userRemoteConfigs: [[credentialsId: medulaCredentialsId, url: 'https://github.com/Teladoc/medulla']],
      extensions: [[ $class: 'RelativeTargetDirectory', relativeTargetDir: "${dir}"]]
  ])
}