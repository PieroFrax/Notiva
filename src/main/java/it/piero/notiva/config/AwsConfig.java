package it.piero.notiva.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.textract.TextractClient;

@Configuration
public class AwsConfig {

    @Value("${aws.defaultTextractRegion}")
    private String textractRegion;

    @Value("${aws.defaultTextractEndpoint}")
    private String textractEndpoint;

    @Value("${aws.defaultRekognitionRegion}")
    private String rekognitionRegion;

    @Value("${aws.defaultRekognitionEndpoint}")
    private String rekognitionEndpoint;

    @Bean
    public TextractClient textractClient() {
        return TextractClient.builder()
                .region(Region.of(textractRegion))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean
    public RekognitionClient rekognitionClient() {
        return RekognitionClient.builder()
                .region(Region.of(rekognitionRegion))
                .endpointOverride(java.net.URI.create(rekognitionEndpoint))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
