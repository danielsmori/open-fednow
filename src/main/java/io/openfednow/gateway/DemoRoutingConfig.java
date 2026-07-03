package io.openfednow.gateway;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Maps {@code /demo/} to the landing page at
 * {@code classpath:/static/demo/index.html}.
 *
 * <p>Spring Boot's default welcome-page mechanism only resolves the top-level
 * {@code index.html} in the static resource root; a request for a subdirectory
 * like {@code /demo/} is not automatically served from the subdirectory's
 * {@code index.html}. Adding an explicit forward here means a visitor can hit
 * {@code /demo/} directly instead of memorising {@code /demo/index.html}.
 */
@Configuration
public class DemoRoutingConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/demo/").setViewName("forward:/demo/index.html");
        registry.addViewController("/demo").setViewName("redirect:/demo/");
    }
}
