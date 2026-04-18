package ua.mitit.ids.benchmark;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Programmatic JMH runner — an alternative to {@code java -jar benchmarks.jar}.
 *
 * <p>Usage: {@code BenchmarkRunner [include-regex] [output-json-path]}. Default include is all
 * benchmarks under this module; default output is {@code results/benchmark-<timestamp>.json}.
 */
public final class BenchmarkRunner {

  private BenchmarkRunner() {}

  public static void main(String[] args) throws Exception {
    String include = args.length > 0 ? args[0] : "ua\\.mitit\\.ids\\.benchmark\\..*";
    String output =
        args.length > 1
            ? args[1]
            : "results/benchmark-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + ".json";

    Files.createDirectories(Paths.get("results"));

    var opts =
        new OptionsBuilder()
            .include(include)
            .resultFormat(ResultFormatType.JSON)
            .result(output)
            .shouldFailOnError(true)
            .build();

    new Runner(opts).run();
    System.out.println("Results written to " + output);
  }
}
