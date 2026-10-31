package app.theveil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;
@Configuration
public class WebConfig implements WebMvcConfigurer {
  @Value("${app.host-origin:http://localhost:5173}") private String origin;
  @Override public void addCorsMappings(CorsRegistry registry) { registry.addMapping("/api/**").allowedOrigins(origin,"http://localhost:5173").allowedMethods("GET","POST","PUT","OPTIONS").allowedHeaders("Authorization","Content-Type"); }
}
