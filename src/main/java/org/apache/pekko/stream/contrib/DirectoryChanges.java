/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package org.apache.pekko.stream.contrib;

import org.apache.pekko.NotUsed;
import org.apache.pekko.japi.Pair;
import org.apache.pekko.stream.Attributes;
import org.apache.pekko.stream.Outlet;
import org.apache.pekko.stream.SourceShape;
import org.apache.pekko.stream.javadsl.Source;
import org.apache.pekko.stream.stage.AbstractOutHandler;
import org.apache.pekko.stream.stage.GraphStage;
import org.apache.pekko.stream.stage.GraphStageLogic;
import org.apache.pekko.stream.stage.TimerGraphStageLogic;

import com.sun.nio.file.SensitivityWatchEventModifier;

import scala.Tuple2;
import scala.concurrent.duration.FiniteDuration;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayDeque;
import java.util.Queue;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * Watches a file system directory and streams change events from it.
 *
 * Note that the JDK watcher is notoriously slow on some platform (up to 1s after event actually happened on OSX for example)
 *
 * @deprecated since 0.10, use Pekko Connectors' implementation instead https://pekko.apache.org/docs/pekko-connectors/current/file.html#listing-directory-contents
 */
@Deprecated
public final class DirectoryChanges extends GraphStage<SourceShape<Pair<Path, DirectoryChanges.Change>>> {

  public enum Change {
    Modification,
    Creation,
    Deletion
  }

  private final static Attributes DEFAULT_ATTRIBUTES = Attributes.name("DirectoryChanges");

  private final Path directoryPath;
  private final FiniteDuration pollInterval;
  private final int maxBufferSize;
  private final Outlet<Pair<Path, Change>> out = Outlet.create("DirectoryChanges.out");
  private final SourceShape<Pair<Path, Change>> shape = SourceShape.of(out);

  /**
   * @param directoryPath Directory to watch
   * @param pollInterval Interval between polls to the JDK watch service when a push comes in and there was no changes, if
   *                     the JDK implementation is slow, it will not help lowering this
   * @param maxBufferSize Maximum number of buffered directory changes before the stage fails
   */
  public DirectoryChanges(Path directoryPath, FiniteDuration pollInterval, int maxBufferSize) {
    this.directoryPath = directoryPath;
    this.pollInterval = pollInterval;
    this.maxBufferSize = maxBufferSize;
  }

  @Override
  public SourceShape<Pair<Path, Change>> shape() {
    return shape;
  }

  @Override
  public Attributes initialAttributes() {
    return DEFAULT_ATTRIBUTES;
  }

  @Override
  public GraphStageLogic createLogic(Attributes inheritedAttributes) throws IOException {
    if (!Files.exists(directoryPath)) throw new IllegalArgumentException("The path: '" + directoryPath + "' does not exist");
    if (!Files.isDirectory(directoryPath)) throw new IllegalArgumentException("The path '" + directoryPath + "' is not a directory");

    return new TimerGraphStageLogic(shape) {
      private final Queue<Pair<Path, Change>> buffer = new ArrayDeque<>();
      private final WatchService service = directoryPath.getFileSystem().newWatchService();
      private final WatchKey watchKey = directoryPath.register(
        service,
        new WatchEvent.Kind<?>[] { ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE, OVERFLOW },
        // this is com.sun internal, but the service is useless on OSX without it
        SensitivityWatchEventModifier.HIGH
      );

      {
        setHandler(out, new AbstractOutHandler(){

          @Override
          public void onPull() throws Exception {
            if (!buffer.isEmpty()) {
              pushHead();
            } else {
              doPoll();
              if (!buffer.isEmpty()) {
                pushHead();
              } else {
                schedulePoll();
              }
            }
          }
        });
      }

      @Override
      public void onTimer(Object timerKey) {
        if (!isClosed(out)) {
          doPoll();
          if (!buffer.isEmpty()) {
            pushHead();
          } else {
            schedulePoll();
          }
        }
      }

      @Override
      public void postStop() {
        try {
          if (watchKey.isValid()) watchKey.cancel();
          service.close();
        } catch (Exception ex) {
          // Remove when #21168 is in a release
          throw new RuntimeException(ex);
        }
      }

      private void pushHead() {
        final Pair<Path, Change> head = buffer.poll();
        if (head != null) {
          push(out, head);
        }
      }

      private void schedulePoll() {
        scheduleOnce("poll", pollInterval);
      }

      private void doPoll() {
        try {
          for (WatchEvent<?> event: watchKey.pollEvents()) {
            final WatchEvent.Kind<?> kind = event.kind();

            if (OVERFLOW.equals(kind)) {
              // overflow means that some file system change events may have been missed,
              // that may be ok for some scenarios but to make sure it does not pass unnoticed we fail the stage
              failStage(new RuntimeException("Overflow from watch service: '" + directoryPath + "'"));

            } else {
              // if it's not an overflow it must be a Path event
              @SuppressWarnings("unchecked")
              final Path path = (Path) event.context();
              final Path absolutePath = directoryPath.resolve(path);
              final Change change = kindToChange(kind);

              buffer.add(new Pair<>(absolutePath, change));
              if (buffer.size() > maxBufferSize) {
                failStage(new RuntimeException("Max event buffer size " +
                  maxBufferSize + " reached for $path"));
              }
            }

          }
        } finally {
          if (!watchKey.reset()) {
            // directory no longer accessible
            completeStage();
          }
        }
      }



      // convert from the parametrized API to our much nicer API enum
      private Change kindToChange(WatchEvent.Kind<?> kind) {
        final Change change;
        if (kind.equals(ENTRY_CREATE)) {
          change = Change.Creation;
        } else if (kind.equals(ENTRY_DELETE)) {
          change = Change.Deletion;
        } else if (kind.equals(ENTRY_MODIFY)) {
          change = Change.Modification;
        } else {
          throw new RuntimeException("Unexpected kind of event gotten from watch service for path '" +
            directoryPath + "': " + kind);
        }
        return change;
      }


    };
  }

  @Override
  public String toString() {
    return "DirectoryChanges(" + directoryPath + ')';
  }


  // factory methods

  /**
   * Java API
   *
   * @param directoryPath Directory to watch
   * @param pollInterval Interval between polls to the JDK watch service when a push comes in and there was no changes, if
   *                     the JDK implementation is slow, it will not help lowering this
   * @param maxBufferSize Maximum number of buffered directory changes before the stage fails
   */
  public static Source<Pair<Path, Change>, NotUsed> create(Path directoryPath, FiniteDuration pollInterval, int maxBufferSize) {
    return Source.fromGraph(new DirectoryChanges(directoryPath, pollInterval, maxBufferSize));
  }

  /**
   * Scala API
   *
   * @param directoryPath Directory to watch
   * @param pollInterval Interval between polls to the JDK watch service when a push comes in and there was no changes, if
   *                     the JDK implementation is slow, it will not help lowering this
   * @param maxBufferSize Maximum number of buffered directory changes before the stage fails
   */
  public static org.apache.pekko.stream.scaladsl.Source<Tuple2<Path, Change>, NotUsed> apply(Path directoryPath, FiniteDuration pollInterval, int maxBufferSize) {
    return create(directoryPath, pollInterval, maxBufferSize)
      .map((Pair<Path, Change> pair) -> Tuple2.apply(pair.first(), pair.second()))
      .asScala();
  }

}
