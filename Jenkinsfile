void setBuildStatus(String message, String state) {
  step([
    $class: "GitHubCommitStatusSetter",
    reposSource: [$class: "ManuallyEnteredRepositorySource", url: "https://github.com/n1analytics/entity-service"],
    contextSource: [$class: 'ManuallyEnteredCommitContextSource', context: 'jenkins'],
    statusResultSource: [ $class: "ConditionalStatusResultSource", results: [[$class: "AnyBuildResult", message: message, state: state]] ]
  ]);
}

node('docker') {
  stage('Checkout') {
      checkout scm
  }

  // Login to quay.io
  withCredentials([usernamePassword(credentialsId: 'quayion1analyticsbuilder', usernameVariable: 'USERNAME_QUAY', passwordVariable: 'PASSWORD_QUAY')]) {
    sh 'docker login -u=${USERNAME_QUAY} -p=${PASSWORD_QUAY} quay.io'
  }

  def errorMsg = "Unknown failure";

  try {

    stage('Docker Build') {

      try {
          sh """
            cd backend
            docker build -t quay.io/n1analytics/entity-app .
            cd ../frontend
            docker build -t quay.io/n1analytics/entity-nginx .
            cd ../docs
            docker build -t quay.io/n1analytics/entity-app:doc-builder .
          """
          setBuildStatus("Docker build complete", "PENDING");
      } catch (err) {
        errorMsg = "Failed to build docker images";
        throw err;
      }
    }

    stage('Compose Deploy') {
      try {
        sh '''
        # Stop and remove any existing entity service
        docker-compose -f tools/docker-compose.yml -f tools/ci.yml -p entityservicetest down -v

        # Start all the containers (including tests)
        docker-compose -f tools/docker-compose.yml -f tools/ci.yml -p entityservicetest up -d
        '''
        setBuildStatus("Integration test in progress", "PENDING");
      } catch (err) {
        errorMsg = "Failed to start CI integration test with docker compose";
        throw err;
      }
    }

    stage('Integration Tests') {
      try {
        sh '''
          sleep 2
          docker logs -t -f entityservicetest_ci_1
          exit `docker inspect --format='{{.State.ExitCode}}' entityservicetest_ci_1`
        '''
        setBuildStatus("Integration tests complete", "SUCCESS");
      } catch (err) {
        errorMsg = "Integration tests didn't pass";
        throw err;
      }
    }

    stage('Documentation') {
      try {
        sh '''
          mkdir -p htmlbuild
          rm -fr htmlbuild/*
          docker run -v `pwd`/docs:/src -v `pwd`/htmlbuild:/build quay.io/n1analytics/entity-app:doc-builder
        '''
        setBuildStatus("Documentation Built", "SUCCESS");

        publishHTML (target: [
            allowMissing: false,
            alwaysLinkToLastBuild: false,
            keepAll: true,
            reportDir: 'htmlbuild',
            reportFiles: 'index.html',
            reportName: "Entity Service Docs"
        ])
      } catch (err) {
        errorMsg = "Couldn't build docs";
        throw err;
      }
    }

    stage('Package Release') {
      try {
        sh '''
          ./tools/make-release.sh
          ls /tmp/*.zip
        '''
        setBuildStatus("Release Packaged", "SUCCESS");


        //archiveArtifacts artifacts: "/tmp/n1-es-*.zip"

      } catch (err) {
        errorMsg = "Couldn't build release";
        throw err;
      }
    }

    stage('Publish') {
      // Login to quay.io
      withCredentials([usernamePassword(credentialsId: 'quayion1analyticsbuilder', usernameVariable: 'USERNAME_QUAY', passwordVariable: 'PASSWORD_QUAY')]) {
        sh 'docker login -u=${USERNAME_QUAY} -p=${PASSWORD_QUAY} quay.io'

        try {
            sh '''
              ./tools/upload.sh
            '''
            setBuildStatus("Published Docker Images", "SUCCESS");
        } catch (Exception err) {
          errorMsg = "Publishing docker images to quay.io failed";
          throw err
        }

        sh 'docker logout quay.io'
      }
    }

  } catch (err) {
        currentBuild.result = 'FAILURE'
        setBuildStatus(errorMsg,  "FAILURE");
  } finally {
    sh './tools/ci_cleanup.sh'
  }

}