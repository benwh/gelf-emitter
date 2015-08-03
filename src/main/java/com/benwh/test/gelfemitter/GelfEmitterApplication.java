package com.benwh.test.gelfemitter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;


@EnableAutoConfiguration
public class GelfEmitterApplication implements CommandLineRunner {

	private final Logger logger = LoggerFactory.getLogger(getClass());

    public static void main(String[] args) throws Exception {
        SpringApplication.run(GelfEmitterApplication.class, args);
    }

	@Override
	public void run(String... args) throws Exception {

		while (true) {
			logger.debug("Test debug");
			logger.info("Test Info");
			logger.warn("Test warn");
			logger.error("Test err");
			try {
				throw new RuntimeException("Die");
			} catch(RuntimeException e) {
				logger.warn("Logging throwable: ", e);
			}
			logger.warn(buildGiantString(1000));
			// stackOverflow();
			Thread.sleep(5000);
		}
	}


	private void stackOverflow() {
	    stackOverflow();
	}

	private String buildGiantString(int num) {
	    StringBuilder sb = new StringBuilder();
	    for (int i = 0; i <= num; i++) {
		sb.append("Line " + i + "\n");
	    }
	    String result = sb.toString();

	    return num + " - " + result.length() + "\n" + result;
	}
}
