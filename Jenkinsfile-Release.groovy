@Library("N1Pipeline@0.0.19")
import com.n1analytics.docker.DockerContainer;
import com.n1analytics.docker.DockerUtils;
import com.n1analytics.docker.QuayIORepo
import com.n1analytics.git.GitUtils;
import com.n1analytics.git.GitCommit;
import com.n1analytics.git.GitRepo;


String gitContextKubernetesDeployment = "release-kubernetes-deployment"

GitCommit gitCommit


node('helm && kubectl') {

    stage('K8s Deployment') {
        // Knowing this git commit will enable us to send to github the corresponding status.
        gitCommit = GitUtils.checkoutFromSCM(this)
        gitCommit.setInProgressStatus(gitContextKubernetesDeployment)

        // Pre-existant configuration file available from jenkins
        CLUSTER_CONFIG_FILE_ID = "awsClusterConfig"

        DEPLOYMENT = "linkage-${BRANCH_NAME}-${BUILD_NUMBER}".replaceAll("[-_./]", "").toLowerCase()
        NAMESPACE = "default"

        // Consider using BRANCH_NAME here:
        def TAG = sh(script: """python tools/get_docker_tag.py develop app""", returnStdout: true).trim()
        def NGINXTAG = sh(script: """python tools/get_docker_tag.py develop nginx""", returnStdout: true).trim()


        configFileProvider([configFile(fileId: CLUSTER_CONFIG_FILE_ID, variable: 'KUBECONFIG')]) {
            withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'aws_jenkins', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                try {
                    dicTestVersions = [
                            "api"    : [
                                    "www"   : [
                                            "image": [
                                                    "tag": NGINXTAG
                                            ]
                                    ],
                                    "app"   : [
                                            "image": [
                                                    "tag": TAG
                                            ]
                                    ],
                                    "dbinit": [
                                            "image": [
                                                    "tag": TAG
                                            ]
                                    ]
                            ],
                            "workers": [
                                    "image": [
                                            "tag": TAG
                                    ]
                            ]
                    ]

                    timeout(time: 24, unit: 'HOURS') {
                        dir("deployment/entity-service") {
                            if (fileExists("test-versions.yaml")) {
                                sh "rm test-versions.yaml"
                            }
                            writeYaml(file: "test-versions.yaml", data: dicTestVersions)
                            sh """
                helm version
                helm dependency update
                helm install --wait --namespace ${NAMESPACE} --name ${DEPLOYMENT} . \
                    -f values.yaml -f test-versions.yaml \
                    --set api.app.debug=false \
                    --set workers.debug=false \
                    --set api.ingress.enabled=false
                """
                            // give the cluster a chance to provision volumes etc, assign an IP to the service, then create a new job to test it
                            sleep(time: 120, unit: "SECONDS")
                        }

                        String serviceIP = sh(script: """
                kubectl get services -lapp=${DEPLOYMENT}-entity-service -o jsonpath="{.items[0].spec.clusterIP}"
            """, returnStdout: true).trim()

                        pvc = createK8sTestJob(DEPLOYMENT, QuayIORepo.ENTITY_SERVICE_APP.getRepo() + ":" + TAG, serviceIP)

                        sleep(time: 120, unit: "SECONDS")

                        def jobPodName = sh(script: """
                            kubectl get pods -l deployment=${DEPLOYMENT} -o jsonpath="{.items[0].metadata.name}"
                        """, returnStdout: true).trim()
                        echo jobPodName

                        sh """
                            # Show the output
                            kubectl logs -f $jobPodName
                        """

                        resultFile = getResultsFromK8s(DEPLOYMENT, pvc)

                        junit resultFile

                    }
                    gitCommit.setSuccessStatus(gitContextKubernetesDeployment)
                } catch (error) { // timeout reached or input false
                    print("Error in k8s deployment stage:\n" + error)
                    gitCommit.setFailStatus(gitContextKubernetesDeployment)
                    throw new Exception("The k8s integration tests failed.")
                } finally {
                    try {
                        sh """
            helm delete --purge ${DEPLOYMENT}
            kubectl delete job -lapp=${DEPLOYMENT}-entity-service
            kubectl delete job ${DEPLOYMENT}-test
            kubectl delete all -ldeployment=${DEPLOYMENT}
            kubectl delete all -lrelease=${DEPLOYMENT}
            """
                    } catch (Exception err) {
                        print("Error in final cleanup, Ignoring:\n" + err)
                        // Do nothing on purpose.
                    }
                }
            }
        }
    }
}


void createK8sFromManifest(LinkedHashMap<String, Object> manifest) {
    String fileName = "tmp.yaml"
    if (fileExists(fileName)) {
        sh "rm " + fileName
    }
    writeYaml(file: fileName, data: manifest)
    String shCommand = "kubectl create -f " + fileName
    sh shCommand
    sh "rm " + fileName
}

String getResultsFromK8s(String deploymentName, String pvcName) {
    String pod_name = "${BRANCH_NAME}-${BUILD_NUMBER}-tmppod"

    def k8s_pod_manifest = [
            "apiVersion": "v1",
            "kind"      : "Pod",
            "metadata"  : [
                    "name"  : pod_name,
                    "labels": [
                            "deployment": deploymentName
                    ]
            ],
            "spec"      : [
                    "restartPolicy": "Never",
                    "volumes"      : [
                            [
                                    "name"                 : "results",
                                    "persistentVolumeClaim": [
                                            "claimName": pvcName
                                    ]
                            ]

                    ],

                    "containers"   : [
                            [
                                    "name"        : "resultpod",
                                    "image"       : "python",
                                    "command"     : [
                                            "sleep",
                                            "3600"
                                    ],
                                    "volumeMounts": [[
                                                             "mountPath": "/mnt",
                                                             "name"     : "results"
                                                     ]]
                            ]
                    ],
            ]
    ]

    createK8sFromManifest(k8s_pod_manifest)
    sleep(time: 60, unit: "SECONDS")
    String out = "k8s-results.xml"
    sh """
      # fetch the results from the running pod
      kubectl cp $NAMESPACE/$pod_name:/mnt/results.xml $out

      # delete the temp pod
      kubectl delete pod $pod_name

      # delete the pvc
      kubectl delete pvc -l deployment=$deploymentName
  """
    return out
}

String createK8sTestJob(String deploymentName, String imageNameWithTag, String serviceIP) {
    String pvc_name = deploymentName + "-test-results"

    def k8s_pvc_manifest = [
            "apiVersion": "v1",
            "kind"      : "PersistentVolumeClaim",
            "metadata"  : [
                    "name"  : pvc_name,
                    "labels": [
                            "jobgroup"  : "jenkins-es-integration-test",
                            "deployment": deploymentName
                    ]
            ],
            "spec"      : [
                    "storageClassName": "default",
                    "accessModes"     : [
                            "ReadWriteOnce"
                    ],
                    "resources"       : [
                            "requests": [
                                    "storage": "1Gi"
                            ]
                    ]

            ]
    ]


    def k8s_job_manifest = [
            "apiVersion": "batch/v1",
            "kind"      : "Job",
            "metadata"  : [
                    "name"  : deploymentName + "-test",
                    "labels": [
                            "jobgroup"  : "jenkins-es-integration-test",
                            "deployment": deploymentName
                    ]
            ],
            "spec"      : [
                    "completions": 1,
                    "parallelism": 1,
                    "template"   : [
                            "metadata": [
                                    "labels": [
                                            "jobgroup"  : "jenkins-es-integration-test",
                                            "deployment": deploymentName
                                    ]
                            ],
                            "spec"    : [
                                    "securityContext" : [
                                            "runAsUser": 0,
                                            "fsGroup"  : 0
                                    ],
                                    "restartPolicy"   : "Never",
                                    "volumes"         : [
                                            [
                                                    "name"                 : "results",
                                                    "persistentVolumeClaim": [
                                                            "claimName": pvc_name
                                                    ]
                                            ]

                                    ],

                                    "containers"      : [
                                            [
                                                    "name"           : "entitytester",
                                                    "image"          : imageNameWithTag,
                                                    "imagePullPolicy": "Always",
                                                    "env"            : [
                                                            [
                                                                    "name" : "ENTITY_SERVICE_URL",
                                                                    "value": "http://" + serviceIP + "/api/v1"
                                                            ],
                                                            [
                                                                    "name" : "ENTITY_SERVICE_RUN_SLOW_TESTS",
                                                                    "value": "False"
                                                            ]
                                                    ],
                                                    "command"        : [
                                                            "python",
                                                            "-m",
                                                            "pytest",
                                                            "entityservice/tests",
                                                            "--junit-xml=/mnt/results.xml",
                                                            "-x"
                                                    ],
                                                    "volumeMounts"   : [[
                                                                                "mountPath": "/mnt",
                                                                                "name"     : "results"
                                                                        ]]
                                            ]
                                    ],
                                    "imagePullSecrets": [
                                            [
                                                    "name": "n1-quay-pull-secret"
                                            ]
                                    ]
                            ]

                    ]
            ]
    ]

    createK8sFromManifest(k8s_pvc_manifest)
    createK8sFromManifest(k8s_job_manifest)

    return pvc_name
}