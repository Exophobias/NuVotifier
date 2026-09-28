// Docker Official Image tag verified in docker-library/official-images on 2026-09-27.
pipeline {
    agent {
        docker { image 'eclipse-temurin:25-jdk' }
    }

    options {
        timeout(time: 20, unit: 'MINUTES')
        disableConcurrentBuilds()
    }

    stages {
        stage('Verify, test and package') {
            steps {
                // Official Gradle 9.8.0 wrapper checksum; distribution checksum is in its properties.
                sh 'echo "238e777fcddd7e34f9708186085def2abd6e08e658505b38718d79d74c21abd5  gradle/wrapper/gradle-wrapper.jar" | sha256sum --check --strict'
                sh './gradlew --no-daemon --dependency-verification strict clean verifyRelease'
            }
        }
    }

    post {
        always {
            junit testResults: '**/build/test-results/test/*.xml', allowEmptyResults: true
            archiveArtifacts artifacts: 'universal/build/libs/*.jar,**/build/reports/tests/test/**', allowEmptyArchive: true, fingerprint: true
        }
    }
}
