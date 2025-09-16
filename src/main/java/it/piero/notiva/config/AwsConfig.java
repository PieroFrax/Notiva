package it.piero.notiva.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.textract.TextractClient;

@Configuration
public class AwsConfig {

    @Value("${aws.defaultTextractRegion}")
    private String textractRegion;

    @Bean
    public TextractClient textractClient() {
        return TextractClient.builder()
                .region(Region.of(textractRegion))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

}
