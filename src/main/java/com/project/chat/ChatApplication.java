package com.project.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.Executors;

@SpringBootApplication
@EnableAsync
@EnableScheduling
@Slf4j
public class ChatApplication {

	public static void main(String[] args) {
		// Java 21: configure virtual threads as the default for Tomcat
		// This allows the server to handle thousands of concurrent connections
		// without the overhead of platform thread-per-request blocking.
		System.setProperty("spring.threads.virtual.enabled", "true");

		SpringApplication app = new SpringApplication(ChatApplication.class);
		app.run(args);
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onApplicationReady() {
		String serverId = System.getenv().getOrDefault("SERVER_ID", "server-1");
		log.info("╔══════════════════════════════════════════════════════════╗");
		log.info("║   Distributed Real-Time Chat Platform STARTED            ║");
		log.info("║   Server ID  : {}                                  ║", serverId);
		log.info("║   Java       : {}                                    ║", Runtime.version());
		log.info("║   Processors : {}                                         ║", Runtime.getRuntime().availableProcessors());
		log.info("╚══════════════════════════════════════════════════════════╝");

		// Demonstrate Java 21 virtual thread creation at startup for logging
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			executor.submit(() ->
					log.info("[VirtualThread] Application startup health probe running on thread: {}",
							Thread.currentThread().isVirtual() ? "VIRTUAL" : "PLATFORM")
			);
		}
	}
}
