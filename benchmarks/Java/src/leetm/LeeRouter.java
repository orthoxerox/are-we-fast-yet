/*
 * BSD License
 *
 * Copyright (c) 2007, The University of Manchester (UK)
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 *     - Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     - Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     - Neither the name of the University of Manchester nor the names
 *       of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written
 *       permission.

 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 *  OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package leetm;

import java.util.Vector;

public class LeeRouter {

  final int                    gridSize;

  static final int             EMPTY                = 0;
  static final int             TEMP_EMPTY           = 10000;
  static final int             OCC                  = 5120;
  static final int             VIA                  = 6000;
  static final int             BVIA                 = 6001;
  static final int             TRACK                = 8192;
  static final int             MAX_WEIGHT           = 1;

  final Grid   grid;
  final Object gridLock = new Object();

  int netNo = 0;

  // note these very useful arrays
  static final int[][] dx = {{-1, 1, 0, 0}, {0, 0, -1, 1}};

  // to help look NSEW.
  static final int[][] dy = {{0, 0, -1, 1}, {-1, 1, 0, 0}};

  int failures   = 0;
  int numVias    = 0;
  int forcedVias = 0;

  String inputLine;
  int    linepos = 0;

  final Object                 queueLock            = new Object();

  final WorkQueue              work;

  public static final boolean  DEBUG = false;

  public LeeRouter(final String[] data, final int gridSize, final boolean rel) {
    this.gridSize = gridSize;
    if (DEBUG) {
      System.out.println("Creating grid...");
    }
    grid = new Grid(gridSize, gridSize, 2, rel); // the Lee 3D Grid;
    if (DEBUG) {
      System.out.println("Done creating grid");
    }
    work = new WorkQueue(); // empty
    if (DEBUG) {
      System.out.println("Parsing data...");
    }
    parseData(data);

    if (DEBUG) {
      System.out.println("Done parsing data");
      System.out.println("Adding weights...");
    }
    grid.addweights();
    if (DEBUG) {
      System.out.println("Done adding weights");
    }
    work.sort();
  }

  private void parseData(final String[] data) {
    // Read very simple HDL file
    for (String line : data) {
      inputLine = line;
      linepos = 0;

      char c = readChar();
      if (c == 'E') {
        break; // end of file
      }

      if (c == 'C') { // chip bounding box
        int x0 = readInt();
        int y0 = readInt();
        int x1 = readInt();
        int y1 = readInt();
        grid.occupy(x0, y0, x1, y1);
      }

      if (c == 'P') { // pad
        int x0 = readInt();
        int y0 = readInt();
        grid.occupy(x0, y0, x0, y0);
      }

      if (c == 'J') { // join connection pts
        int x0 = readInt();
        int y0 = readInt();
        int x1 = readInt();
        int y1 = readInt();
        netNo++;
        work.next = work.enQueue(x0, y0, x1, y1, netNo);
      }
    }
  }

  public WorkQueue getNextTrack() {
    synchronized (queueLock) {
      if (work.next != null) {
        return work.deQueue();
      }
    }
    return null;
  }

  public boolean layNextTrack(final WorkQueue q, final int[][][] tempg) {
    // start transaction
    boolean done = false;
    synchronized (gridLock) {
      done = connect(q.x1, q.y1, q.x2, q.y2, q.nn, tempg, grid);
    }
    return done;
    // end transaction
  }

  private char readChar() {
    while ((inputLine.charAt(linepos) == ' ')
        && (inputLine.charAt(linepos) == '\t')) {
      linepos++;
    }
    char c = inputLine.charAt(linepos);
    if (linepos < inputLine.length() - 1) {
      linepos++;
    }
    return c;
  }

  private int readInt() {
    while ((inputLine.charAt(linepos) == ' ')
        || (inputLine.charAt(linepos) == '\t')) {
      linepos++;
    }
    int fpos = linepos;
    while ((linepos < inputLine.length())
        && (inputLine.charAt(linepos) != ' ')
        && (inputLine.charAt(linepos) != '\t')) {
      linepos++;
    }
    int n = Integer.parseInt(inputLine.substring(fpos, linepos));
    return n;
  }

  public boolean ok(final int x, final int y) {
    // checks that point is actually within the bounds
    // of grid array
    return (x > 0 && x < gridSize - 1 && y > 0 && y < gridSize - 1);
  }

  public boolean expandFromTo(final int x, final int y, final int xGoal,
      final int yGoal, final int num, final int[][][] tempg, final Grid grid) {
    // this method should use Lee's expansion algorithm from
    // coordinate (x,y) to (xGoal, yGoal) for the num iterations
    // it should return true if the goal is found and false if it is not
    // reached within the number of iterations allowed.

    // g[xGoal][yGoal][0] = EMPTY; // set goal as empty
    // g[xGoal][yGoal][1] = EMPTY; // set goal as empty
    Vector<Frontier> front = new Vector<Frontier>();
    Vector<Frontier> tmp_front = new Vector<Frontier>();
    tempg[x][y][0] = 1; // set grid (x,y) as 1
    tempg[x][y][1] = 1; // set grid (x,y) as 1
    boolean trace1 = false;
    front.addElement(new Frontier(x, y, 0, 0));
    front.addElement(new Frontier(x, y, 1, 0)); // we can start from either
    // side
    if (DEBUG) {
      System.out.println("Expanding " + x + " " + y + " " + xGoal + " " + yGoal);
    }
    int extra_iterations = 50;
    boolean reached0 = false;
    boolean reached1 = false;
    while (!front.isEmpty()) {
      while (!front.isEmpty()) {
        int weight, prev_val;
        Frontier f = front.elementAt(0);
        front.removeElementAt(0);
        if (f.dw > 0) {
          tmp_front.addElement(new Frontier(f.x, f.y, f.z, f.dw - 1));
        } else {
          if (trace1) {
            if (DEBUG) {
              System.out.println("X " + f.x + " Y " + f.y + " Z " + f.z + " DW "
                  + f.dw + " processing - val " + tempg[f.x][f.y][f.z]);
            }
          }
          // int dir_weight = 1;
          weight = grid.getPoint(f.x, f.y + 1, f.z) + 1;
          prev_val = tempg[f.x][f.y + 1][f.z];
          boolean reached = (f.x == xGoal) && (f.y + 1 == yGoal);
          if ((prev_val > tempg[f.x][f.y][f.z] + weight) && (weight < OCC)
              || reached) {
            if (ok(f.x, f.y + 1)) {
              tempg[f.x][f.y + 1][f.z] = tempg[f.x][f.y][f.z] + weight; // looking
                                                                        // north
              if (!reached) {
                tmp_front.addElement(new Frontier(f.x, f.y + 1, f.z, 0));
              }
            }
          }
          weight = grid.getPoint(f.x + 1, f.y, f.z) + 1;
          prev_val = tempg[f.x + 1][f.y][f.z];
          reached = (f.x + 1 == xGoal) && (f.y == yGoal);
          if ((prev_val > tempg[f.x][f.y][f.z] + weight) && (weight < OCC)
              || reached) {
            if (ok(f.x + 1, f.y)) {
              tempg[f.x + 1][f.y][f.z] = tempg[f.x][f.y][f.z] + weight; // looking
                                                                        // east
              if (!reached) {
                tmp_front.addElement(new Frontier(f.x + 1, f.y, f.z, 0));
              }
            }
          }
          weight = grid.getPoint(f.x, f.y - 1, f.z) + 1;
          prev_val = tempg[f.x][f.y - 1][f.z];
          reached = (f.x == xGoal) && (f.y - 1 == yGoal);
          if ((prev_val > tempg[f.x][f.y][f.z] + weight) && (weight < OCC)
              || reached) {
            if (ok(f.x, f.y - 1)) {
              tempg[f.x][f.y - 1][f.z] = tempg[f.x][f.y][f.z] + weight; // looking
                                                                        // south
              if (!reached) {
                tmp_front.addElement(new Frontier(f.x, f.y - 1, f.z, 0));
              }
            }
          }
          weight = grid.getPoint(f.x - 1, f.y, f.z) + 1;
          prev_val = tempg[f.x - 1][f.y][f.z];
          reached = (f.x - 1 == xGoal) && (f.y == yGoal);
          if ((prev_val > tempg[f.x][f.y][f.z] + weight) && (weight < OCC)
              || reached) {
            if (ok(f.x - 1, f.y)) {
              tempg[f.x - 1][f.y][f.z] = tempg[f.x][f.y][f.z] + weight; // looking
                                                                        // west
              if (!reached) {
                tmp_front.addElement(new Frontier(f.x - 1, f.y, f.z, 0));
              }
            }
          }
          if (f.z == 0) {
            weight = grid.getPoint(f.x, f.y, 1) + 1;
            if ((tempg[f.x][f.y][1] > tempg[f.x][f.y][0]) && (weight < OCC)) {
              tempg[f.x][f.y][1] = tempg[f.x][f.y][0];
              tmp_front.addElement(new Frontier(f.x, f.y, 1, 0));
            }
          } else {
            weight = grid.getPoint(f.x, f.y, 0) + 1;
            if ((tempg[f.x][f.y][0] > tempg[f.x][f.y][1]) && (weight < OCC)) {
              tempg[f.x][f.y][0] = tempg[f.x][f.y][1];
              tmp_front.addElement(new Frontier(f.x, f.y, 0, 0));
            }
          }
          // must check if found goal, if so return TRUE
          reached0 = tempg[xGoal][yGoal][0] != TEMP_EMPTY;
          reached1 = tempg[xGoal][yGoal][1] != TEMP_EMPTY;
          if ((reached0 && !reached1) || (!reached0 && reached1)) {
            extra_iterations = 100;
          }
          if ((extra_iterations == 0) && (reached0 || reached1)
              || (reached0 && reached1)) {
            return true; // if (xGoal, yGoal) can be found in
            // time
          } else {
            extra_iterations--;
          }
        }
      }
      Vector<Frontier> tf;
      tf = front;
      front = tmp_front;
      tmp_front = tf;
    }
    return false;
  }

  private boolean pathFromOtherSide(final int[][][] g, final int X, final int Y,
      final int Z) {
    boolean ok;
    int Zo;
    Zo = 1 - Z; // other side
    int sqval = g[X][Y][Zo];
    if ((sqval == VIA) || (sqval == BVIA)) {
      return false;
    }
    ok = (g[X][Y][Zo] <= g[X][Y][Z]);
    if (ok) {
      ok = (g[X - 1][Y][Zo] < sqval) || (g[X + 1][Y][Zo] < sqval)
          || (g[X][Y - 1][Zo] < sqval) || (g[X][Y + 1][Zo] < sqval);
    }
    return ok;
  }

  private int tlength(final int x1, final int y1, final int x2, final int y2) {
    int sq = (x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1);
    return (int) Math.sqrt(sq);
  }

  private static int deviation(final int x1, final int y1, final int x2,
      final int y2) {
    int xdiff = x2 - x1;
    int ydiff = y2 - y1;
    if (xdiff < 0) {
      xdiff = -xdiff;
    }
    if (ydiff < 0) {
      ydiff = -ydiff;
    }
    if (xdiff < ydiff) {
      return xdiff;
    } else {
      return ydiff;
    }
  }

  public void backtrackFrom(final int xGoal, final int yGoal, final int xStart,
      final int yStart, final int trackNo, final int[][][] tempg,
      final Grid grid) {
    // this method should backtrack from the goal position (xGoal, yGoal)
    // back to the starting position (xStart, yStart) filling in the
    // grid array g with the specified track number trackNo ( + TRACK).

    // ***
    // CurrentPos = Goal
    // Loop
    // Find dir to start back from current position
    // Loop
    // Keep going in current dir and Fill in track (update currentPos)
    // Until box number increases in this current dir
    // Until back at starting point
    // ***
    // int count = 100;
    if (DEBUG) {
      System.out.println("Track " + trackNo + " backtrack " + "Length "
          + tlength(xStart, yStart, xGoal, yGoal));
    }
    // boolean trace = false;
    int zGoal;
    int distsofar = 0;
    if (Math.abs(xGoal - xStart) > Math.abs(yGoal - yStart)) {
      zGoal = 0;
    } else {
      zGoal = 1;
    }
    if (tempg[xGoal][yGoal][zGoal] == TEMP_EMPTY) {
      if (DEBUG) {
        System.out.println("Preferred Layer not reached " + zGoal);
      }
      zGoal = 1 - zGoal;
    }
    int tempY = yGoal;
    int tempX = xGoal;
    int tempZ = zGoal;
    int lastdir = -10;
    while ((tempX != xStart) || (tempY != yStart)) { // PDL: until back

      // at starting point
      boolean advanced = false;
      int mind = 0;
      int dir = 0;
      int min_square = 100000;
      int d;
      for (d = 0; d < 4; d++) { // PDL: Find dir to start back from
        // current position
        if ((tempg[tempX + dx[tempZ][d]][tempY
            + dy[tempZ][d]][tempZ] < tempg[tempX][tempY][tempZ])
            && (tempg[tempX + dx[tempZ][d]][tempY
                + dy[tempZ][d]][tempZ] != TEMP_EMPTY)) {
          if (tempg[tempX + dx[tempZ][d]][tempY
              + dy[tempZ][d]][tempZ] < min_square) {
            min_square = tempg[tempX + dx[tempZ][d]][tempY
                + dy[tempZ][d]][tempZ];
            mind = d;
            dir = dx[tempZ][d] * 2 + dy[tempZ][d]; // hashed dir
            if (lastdir < -2) {
              lastdir = dir;
            }
            advanced = true;
          }
        }
      }
      if (advanced) {
        distsofar++;
      }
      if (DEBUG) {
        System.out.println("Backtracking " + tempX + " " + tempY + " " + tempZ
            + " " + tempg[tempX][tempY][tempZ] + " " + advanced + " " + mind);
      }
      if (pathFromOtherSide(tempg, tempX, tempY,
          tempZ) && ((mind > 1) && // not preferred dir for this layer
              (distsofar > 15) && (tlength(tempX, tempY, xStart, yStart) > 15)
              ||
              // (deviation(tempX,tempY,xStart,yStart) > 3) ||
              (!advanced && ((grid.getPoint(tempX, tempY, tempZ) != VIA)
                  && (grid.getPoint(tempX, tempY, tempZ) != BVIA))))) {
        int tZ = 1 - tempZ; // 0 if 1, 1 if 0
        int viat;
        if (advanced) {
          viat = VIA;
        } else {
          viat = BVIA; // BVIA is nowhere else to go
        }
        // mark via
        tempg[tempX][tempY][tempZ] = viat;
        grid.setPoint(tempX, tempY, tempZ, viat);
        grid.setDebugPoint(tempX, tempY, tempZ, trackNo);

        tempZ = tZ;
        // and the other side
        tempg[tempX][tempY][tempZ] = viat;
        grid.setPoint(tempX, tempY, tempZ, viat);
        grid.setDebugPoint(tempX, tempY, tempZ, trackNo);

        numVias++;
        if (!advanced) {
          forcedVias++;
        }
        if (advanced) {
          if (DEBUG) {
            System.out.println(
                "Via " + distsofar + " " + tlength(tempX, tempY, xStart, yStart)
                    + " " + deviation(tempX, tempY, xStart, yStart));
          }
        }
        distsofar = 0;
      } else {
        if (grid.getPoint(tempX, tempY, tempZ) < OCC) {
          // PDL: fill in track unless connection point
          grid.setPoint(tempX, tempY, tempZ, TRACK);
          if (DEBUG) {
            grid.setDebugPoint(tempX, tempY, tempZ, trackNo);
          }
        } else if (grid.getPoint(tempX, tempY, tempZ) == OCC) {
          if (DEBUG) {
            grid.setDebugPoint(tempX, tempY, tempZ, OCC);
          }
          if (DEBUG) {
            grid.setDebugPoint(tempX, tempY, 1 - tempZ, OCC);
          }
        }
        tempX = tempX + dx[tempZ][mind]; // PDL: updating current
        // position on x axis
        tempY = tempY + dy[tempZ][mind]; // PDL: updating current
        // position on y axis
      }
      lastdir = dir;
    }
    if (DEBUG) {
      System.out.println("Track " + trackNo + " completed");
    }
  }

  public boolean connect(final int xs, final int ys, final int xg, final int yg,
      final int netNo, final int[][][] tempg, final Grid grid) {
    // calls expandFrom and backtrackFrom to create connection
    // This is the only real change needed to make the program
    // transactional.
    // Instead of using the grid 'in place' to do the expansion, we take a
    // copy
    // but the backtrack writes to the original grid.
    // This is not a correctness issue. The transactions would still
    // complete eventually without it.
    // However the expansion writes are only temporary and do not logically
    // conflict.
    // There is a question as to whether a copy is really necessary as a
    // transaction will anyway create
    // its own copy. if we were then to distinguish between writes not to be
    // committed (expansion) and
    // those to be committed (backtrack), we would not need an explicit
    // copy.
    // Taking the copy is not really a computational(time) overhead because
    // it avoids the grid 'reset' phase
    // needed if we do the expansion in place.
    for (int x = 0; x < gridSize; x++) {
      for (int y = 0; y < gridSize; y++) {
        for (int z = 0; z < 2; z++) {
          tempg[x][y][z] = TEMP_EMPTY;
        }
      }
    }
    // call the expansion method to return found/not found boolean
    boolean found = expandFromTo(xs, ys, xg, yg, gridSize * 5, tempg, grid);
    if (found) {
      if (DEBUG) {
        System.out.println("Target (" + xg + ", " + yg + ")... FOUND!");
      }
      backtrackFrom(xg, yg, xs, ys, netNo, tempg, grid); // call the
      // backtrack method
    } // print outcome of expansion method
    else {
      if (DEBUG) {
        System.out.println(
            "Failed to route " + xs + " " + ys + " to " + xg + "  " + yg);
      }
      failures += 1;
    }
    return found;
  }

  public void report() {
    // Open GUI view of PCB
    // view.display();
    // Print the PCB in ASCII, output to file
    // grid.printLayout(true);
    System.out.println("Total Tracks " + netNo + " Failures " + failures
        + " Vias " + numVias + " Forced Vias " + forcedVias);
  }

  public boolean sanityCheck(final int totalLaidTracks, final int file) {
    report();
    if (file == 1) {
      return totalLaidTracks == 1506;
    }
    if (file == 2) {
      return totalLaidTracks == 3101;
    }
    if (file == 3) {
      return totalLaidTracks == 29;
    }
    if (file == 4) {
      return totalLaidTracks == 841;
    }
    if (file == 5) {
      return totalLaidTracks == 6;
    }
    return false;
  }
}