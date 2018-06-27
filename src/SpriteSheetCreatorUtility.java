import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.*;
import java.io.*;
import java.util.*;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * A program for converting png image sequences into compact sprite sheets. A metadata file is exported alongside the
 * sprite sheet, which keeps a record of the position of each frame on the sprite sheet, as well as its width, height,
 * and anchor point location; this information can be used by a program set the bounds of a viewport and then adjust
 * the sprite's location to keep it appropriately "anchored" even as the sprite changes in width or height. It is
 * recommended that the animator adjust his/her canvas so that the desired anchor point (e.g. a character's feet) is at
 * the very center of each frame in their exported png sequence. Each png image file must end with with a frame index,
 * and the index must be a consistent number of digits. For example, running001.png, running002.png, running003.png,
 * etc.
 * <p> The algorithm employed is a modified form of the Basic Packing Algorithm described by Matt Perdeck on Code
 * Project[1]. The program begins by cropping out unnecessary transparent pixels in each png file. The resulting images
 * are then sorted by decreasing height and placed on the sprite sheet by a greedy algorithm, placing the tallest images
 * first. This greedy algorithm is run several times. In the first run, the sprite sheet is assumed to have a height
 * equal to the height of the tallest frame. Then, the sprite sheet's height is increased by 10 pixels and the algorithm
 * is run again. This continues until the resulting width of the sprite sheet is no wider than the widest frame. The
 * arrangement that occupied the smallest area is chosen, and the frames are finally written to file.</p>
 * <p>[1] Perdeck, Matt. 2011. Fast Optimizing Rectangle Packing Algorithm for Building CSS Sprites. Code Project.
 * DOI: https://www.codeproject.com/Articles/210979/Fast-optimizing-rectangle-packing-algorithm-for-bu</p>
 * @author Jonathan D. Roop
 */
public class SpriteSheetCreatorUtility extends Application {
    private final int MAX_HEIGHT = Integer.MAX_VALUE; // maximum allowed height of resulting sprite sheet (in pixels)
    private final int MIN_HEIGHT = 0; // minimum allowed height of resulting sprite sheet (in pixels)

    private File selectedFile;
    private File outputFile;
    private String baseName;
    private ProgressWindow progressWindow;

    public static void main(String args[]){
        launch(args);
    }

    /**
     * The program displays a simple GUI for choosing an image sequence and an output filename.
     * @param primaryStage The stage passed automatically to this method by the launch() method.
     */
    @Override
    public void start(Stage primaryStage){
        primaryStage.setTitle("Sprite Sheet Creator Utility");
        // Adjust the stage size to fit the content nicely:
        primaryStage.setHeight(225);
        primaryStage.setWidth(600);

        // Everything is arranged in a VBox on the scene:
        VBox root = new VBox();
        root.setPadding(new Insets(10,10,10,10));
        Scene primaryScene = new Scene(root);
        primaryStage.setScene(primaryScene);

        // The user must select a file (any file) in the image sequence:
        Label directoryLabel1 = new Label("Image sequence:");
        Label directoryLabel2 = new Label("(none)");
        Button directoryButton = new Button("Select");
        directoryButton.setOnAction((event) -> {
            FileChooser imageSequenceLoc = new FileChooser();
            imageSequenceLoc.setTitle("Select any Image in the Image Sequence");
            if(selectedFile!=null) imageSequenceLoc.setInitialDirectory(selectedFile.getParentFile());
            selectedFile = imageSequenceLoc.showOpenDialog(primaryStage);
            if(selectedFile !=null) directoryLabel2.setText(selectedFile.getPath());
            else directoryLabel2.setText("(none)");
        });

        // The user must specify an output filename:
        Label fileLabel1 = new Label("Output sprite sheet Name:");
        Label fileLabel2 = new Label("(none)");
        Button fileButton = new Button("Select");
        fileButton.setOnAction((event) -> {
            FileChooser outputLocater = new FileChooser();
            outputLocater.setTitle("Specify a Name for the Sprite Sheet");
            String defaultFilename = "spritesheet.png";
            // If the user has already specified an image sequence, suggest a logical output filename:
            if(selectedFile != null){
                defaultFilename = selectedFile.getName();
                defaultFilename = defaultFilename.substring(0,defaultFilename.length()-4); // remove .png extension
                while(java.lang.Character.isDigit(defaultFilename.charAt(defaultFilename.length()-1))){
                    defaultFilename = defaultFilename.substring(0,defaultFilename.length()-1); // remove frame numbers
                }
                defaultFilename = defaultFilename + "_spritesheet.png";
                outputLocater.setInitialDirectory(selectedFile.getParentFile());
            }
            outputLocater.setInitialFileName(defaultFilename);
            outputFile = outputLocater.showSaveDialog(primaryStage);
            if(outputFile !=null){
                if(!outputFile.getName().endsWith(".png")){
                    outputFile = new File(outputFile.getPath() + ".png");
                }
                fileLabel2.setText(outputFile.getPath());
            }
            else fileLabel2.setText("(none)");
        });

        // A modal progress bar window will be displayed when then user clicks "convert":
        progressWindow = new ProgressWindow();

        // The Convert button is on the bottom right:
        HBox hBox = new HBox();
        Region filler = new Region();
        HBox.setHgrow(filler,Priority.ALWAYS);
        Button convert = new Button("Convert");
        hBox.getChildren().addAll(filler, convert);
        convert.setOnAction((event) -> {
            if(selectedFile ==null) {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("Error");
                alert.setHeaderText("You must select a folder containing an image sequence");
                alert.showAndWait();
            }
            else if(outputFile ==null){
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("Error");
                alert.setHeaderText("You must specify a file name for the output sprite sheet");
                alert.showAndWait();
            }
            else{
                progressWindow.initialize();
                progressWindow.show();
                Thread converter = new Thread(this::convert); // Method reference. Ooh, fancy!
                converter.start();
            }
        });

        // Blank spacer Regions separate the options:
        Region spacer = new Region();
        Region spacer2 = new Region();
        VBox.setVgrow(spacer,Priority.ALWAYS);
        VBox.setVgrow(spacer2,Priority.ALWAYS);

        root.getChildren().addAll(directoryLabel1, directoryLabel2, directoryButton, spacer, fileLabel1, fileLabel2,
                fileButton, spacer2, hBox);
        primaryStage.show();
    }

    // A modal window displays a progress bar after the user clicks "convert".
    private class ProgressWindow extends Stage{
        ProgressBar progressBar = new ProgressBar(0.0);
        Label progressDescription = new Label();
        private ProgressWindow(){
            initModality(Modality.APPLICATION_MODAL);
            VBox vBox = new VBox();
            vBox.getChildren().addAll(progressDescription, progressBar);
            vBox.setAlignment(Pos.CENTER);
            setScene(new Scene(vBox));
        }
        private void initialize(){
            progressDescription.setText("Cropping out transparent pixels...");
            progressBar.setProgress(0.0);
        }
    }

    /**
     * This method is run on a new thread after the user clicks the "convert" button. It's the entry point into all of
     * the non-GUI work. The progress bar is also updated from this branch of code.
     */
    private void convert(){
        // Retrieve the base name of the image sequence (strip out .png extension and frame number):
        baseName = selectedFile.getName();
        baseName = baseName.substring(0,baseName.length()-4); // remove .png extension
        while(java.lang.Character.isDigit(baseName.charAt(baseName.length()-1))){
            baseName = baseName.substring(0,baseName.length()-1); // remove frame numbers
        }

        // Create a temporary directory:
        String dirName = selectedFile.getParentFile().getPath()+File.separator + "croppedImagesTempFolder";
        File reducedFilesDir = new File(dirName);
        if(!reducedFilesDir.mkdir()){
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Error");
            alert.setHeaderText("Could not create temp directory. Do you have write permissions for this directory?");
            alert.showAndWait();
            return;
        }

        // Read one image at a time, crop it down to a minimal size, and save the result in the temporary directory:
        List<SpriteFrameMeta> frames = cropImages(reducedFilesDir);
        Platform.runLater(()->progressWindow.progressBar.setProgress(1.0));

        // pack the frames as tightly as possible (well, at least as tightly as the algorithm can accomplish):
        Point dimensions = pack(frames);
        Platform.runLater(()->progressWindow.progressBar.setProgress(1.0));

        // draw the frames into the tightly-packed arrangement and then write the spritesheet file:
        writeImages(dimensions, frames);
        Platform.runLater(()->progressWindow.progressBar.setProgress(1.0));

        // Sort the frames by index and then write the metadata file:
        writeMetaData(frames);
        Platform.runLater((()->progressWindow.progressBar.setProgress(1.0)));

        // Delete temporary files:
        deleteTemp(reducedFilesDir);
        Platform.runLater(()->progressWindow.progressBar.setProgress(1.0));

        // Remove the progress bar:
        Platform.runLater(progressWindow::hide);
    }

    // Crops out unnecessary transparent pixels out of each png file of the image sequence. The cropped images are saved
    // to a temporary directory.
    private List<SpriteFrameMeta> cropImages(File reducedFilesDir){
        List<SpriteFrameMeta> frames = new LinkedList<>();
        File[] files = selectedFile.getParentFile().listFiles();
        int filesProcessed = 0;
        for(File fileIn : files){
            int index = getIndex(fileIn);
            if(index==-1) continue;
            String outputFileName = String.format(reducedFilesDir.getPath() + File.separator + baseName + "%07d" +
                    ".png", index);
            File fileOut = new File(outputFileName);
            try{
                BufferedImage original = ImageIO.read(fileIn);
                int minX = original.getWidth();
                int maxX = 0;
                int minY = original.getHeight();
                int maxY = 0;
                for(int y=0; y<original.getHeight(); y++){
                    for(int x=0; x<original.getWidth(); x++){
                        if(original.getRGB(x,y)>>24!=0){
                            if(x<minX) minX = x;
                            if(x>maxX) maxX = x;
                            if(y<minY) minY = y;
                            if(y>maxY) maxY = y;
                        }
                    }
                }
                int width = maxX-minX+1;
                int height = maxY-minY+1;
                // if width is negative, then the entire frame was probably transparent. Just use 2x2 transparent pixels
                // for that frame:
                if(width<0){
                    minX = 0;
                    minY = 0;
                    width = 1;
                    height = 1;
                }
                double anchorX = original.getWidth()/2.0 - minX;
                double anchorY = original.getHeight()/2.0 - minY;
                frames.add(new SpriteFrameMeta(index, width, height, anchorX, anchorY, fileOut));
                BufferedImage cropped = original.getSubimage(minX,minY,width,height);
                ImageIO.write(cropped,"png",fileOut);
            } catch(IOException e){
                e.printStackTrace();
            }
            filesProcessed++;
            final double filesProcessedTemp = filesProcessed;
            Platform.runLater(()->progressWindow.progressBar.setProgress(filesProcessedTemp/files.length));
        }
        return frames;
    }

    // Packs the collection of Rectangles (SpriteFrameMetas) by running a greedy algorithm for various sprite sheet
    // heights. The most compact arrangement is chosen and its sprite sheet dimensions are returned.
    private Point pack(List<SpriteFrameMeta> frames){
        final int INCREMENT = 10;

        // Sort the frames in order of decreasing height:
        frames.sort((frame1,frame2) -> frame2.height - frame1.height);

        // find the largest frame width (used for the terminating condition in the do-while loop):
        int largestWidth = 0;
        for(SpriteFrameMeta frame : frames) if(frame.width>largestWidth) largestWidth = frame.width;

        // set initial values:
        int maxHeight = Math.max(frames.get(0).height, MIN_HEIGHT);
        int optimalHeight = maxHeight;
        int optimalArea = Integer.MAX_VALUE;

        // Try a range of heights, looking for the optimal solution:
        Platform.runLater(()->{
            progressWindow.progressDescription.setText("Finding a good packing arrangement...");
            progressWindow.progressBar.setProgress(0.0);
        });
        int usedWidth;
        double referenceWidth = 1.0; // for use with the progress bar
        boolean firstRunComplete = false; // for use with the progress bar
        do{
            usedWidth = packIntoHeight(maxHeight, frames);
            if(!firstRunComplete){
                firstRunComplete = true;
                referenceWidth = usedWidth;
            }
            if(usedWidth*maxHeight < optimalArea){
                optimalHeight = maxHeight;
                optimalArea = usedWidth*maxHeight;
            }
            final double progressEstimate = 1.0 - (usedWidth-largestWidth)/(referenceWidth);
            Platform.runLater(()->progressWindow.progressBar.setProgress(progressEstimate));
            maxHeight += INCREMENT;
        } while(usedWidth>largestWidth && maxHeight<MAX_HEIGHT);

        // Pack the frames one more time, using the known optimalHeight:
        usedWidth = packIntoHeight(optimalHeight, frames);
        System.out.println("optimal sheet size: " + usedWidth + "x" + optimalHeight + " which has area="+optimalArea);
        return new Point(usedWidth,optimalHeight);
    }

    // Writes all of the cropped images to a single sprite sheet in the best arrangement that was found.
    private void writeImages(Point dimensions, List<SpriteFrameMeta> frames){
        Platform.runLater(()->{
            progressWindow.progressDescription.setText("writing sprite sheet...");
            progressWindow.progressBar.setProgress(0.0);
        });
        BufferedImage imageOut = new BufferedImage(dimensions.x, dimensions.y, BufferedImage.TYPE_4BYTE_ABGR);
        Graphics g = imageOut.getGraphics();
        try{
            int framesProcessed = 0;
            for(SpriteFrameMeta frame : frames){
                BufferedImage imageIn = ImageIO.read(frame.imageFile);
                g.drawImage(imageIn,frame.x,frame.y,null);
                // For debugging. Draw a green rectangle that shows the bounds of the frame. Note: we subtract 1 from
                // width and height because of the way java draws rectangles. The left and right edges are at x and
                // x+width, so a rectangle with width==1 would occupy 2 pixels horizontally.
                //g.setColor(Color.GREEN);
                //g.drawRect(frame.x, frame.y, frame.width-1, frame.height-1);
                framesProcessed++;
                final double framesProcessedTemp = framesProcessed;
                Platform.runLater(()->progressWindow.progressBar.setProgress(framesProcessedTemp/frames.size()));
            }
            ImageIO.write(imageOut,"png",outputFile);
        } catch(IOException e){
            e.printStackTrace();
        }
    }

    // Writes the csv metadata file for the spritesheet, which records the positions, dimensions, and anchorpoint
    // locations of each frame.
    private void writeMetaData(List<SpriteFrameMeta> frames){
        Platform.runLater(()->{
            progressWindow.progressDescription.setText("Writing metadata file...");
            progressWindow.progressBar.setProgress(0.0);
        });
        frames.sort(Comparator.comparingInt(SpriteFrameMeta::getIndex)); // Using comparingInt. Also fancy!
        String fileName = outputFile.getParentFile().getPath() + File.separator +
                outputFile.getName().substring(0,outputFile.getName().length()-4) + "_metadata.csv";
        try{
            Writer metaOut = new FileWriter(fileName);
            int framesProcessed = 0;
            for(SpriteFrameMeta frame : frames){
                metaOut.write(frame.x + "," + frame.y + "," + frame.width + "," + frame.height + "," +
                        frame.anchorPoint.getX() + "," + frame.anchorPoint.getY() + "\n");
                framesProcessed++;
                final double framesProcessedTemp = framesProcessed;
                Platform.runLater(()->progressWindow.progressBar.setProgress(framesProcessedTemp/frames.size()));
            }
            metaOut.flush();
            metaOut.close();
        } catch(IOException e){
            e.printStackTrace();
        }
    }

    // deletes all files in the temporary directory and then deletes the temp directory itself.
    private void deleteTemp(File reducedFilesDir){
        Platform.runLater(()->{
            progressWindow.progressDescription.setText("Deleting temporary files...");
            progressWindow.progressBar.setProgress(0.0);
        });
        File[] tempFiles = reducedFilesDir.listFiles();
        int filesProcessed = 0;
        boolean failedToDelete = false;
        if(tempFiles!=null){
            for(File file : tempFiles){
                if(!file.delete()) failedToDelete = true;
                filesProcessed++;
                final double filesProcessedTemp = filesProcessed;
                Platform.runLater(()->progressWindow.progressBar.setProgress(filesProcessedTemp/tempFiles.length));
            }
        }
        if(failedToDelete){
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Error");
            alert.setHeaderText("Some temporary files could not be deleted. They are located in the temp folder in" +
                    " the same directory as your sprite sheet.");
            alert.showAndWait();
        }
        else{
            if(!reducedFilesDir.delete()){
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Error");
                alert.setHeaderText("The temp folder could not be deleted. It is located in the same directory as" +
                        " your sprite sheet.");
                alert.showAndWait();
            }
        }
    }

    // Packs the frames into a sprite sheet of a given height using a greedy algorithm.
    private int packIntoHeight(int maxHeight, List<SpriteFrameMeta> frames){
        Cell[][] cells = new Cell[1][1];

        cells[0][0] = new Cell(0,0, Integer.MAX_VALUE, maxHeight);
        for(SpriteFrameMeta frame : frames){
            // place the frame in the upper-leftmost position possible:
            boolean placed = false;
            int rows = cells.length;
            int cols = cells[0].length;
            for(int col=0; !placed && col<cols; col++){
                // note: as soon as a fit is found, we break out of the nested for-loop
                for(int row=0; !placed && row<rows; row++){
                    Cell[][] newCells = frameFitsCell(frame, cells, row, col);
                    if(newCells != null){
                        placed = true;
                        cells = newCells;
                    }
                }
            }
        }

        // Find the actual width used:
        int widthUsed = 0;
        for(SpriteFrameMeta frame : frames) if(frame.x + frame.width > widthUsed) widthUsed = frame.x + frame.width;
        return widthUsed;
    }

    // Attempts to place a frame at the given (row, col) coordinates in the Cell array. If successful, a copy of the
    // Cell array is constructed and returned, with cells sub-divided as appropriate.
    private Cell[][] frameFitsCell(SpriteFrameMeta frame, Cell[][] cells, int row, int col){
        Cell cell = cells[row][col];
        final int maxRow = cells.length-1;
        final int maxCol = cells[0].length-1;
        if(cell.occupied) return null;

        // find the range of cells that would be needed to cover the height and width of this frame
        int endingRow = row;
        int remainingHeight = frame.height - cell.height;
        while(remainingHeight>0){
            endingRow++;
            if(endingRow>maxRow) return null; // not enough height available
            remainingHeight-=cells[endingRow][col].height;
        }
        int endingCol = col;
        int remainingWidth = frame.width - cell.width;
        while(remainingWidth>0){
            endingCol++;
            if(endingCol>maxCol) break; // we have infinite width available to the right at this point, so break.
            for(int r=row; r<=endingRow; r++) if(cells[r][endingCol].occupied) return null; // not enough width
            remainingWidth-=cells[row][endingCol].width;
        }

        // check whether there are any other occupied cells that block the placement
        for(int r=row; r<=endingRow; r++){
            for(int c=col; c<=endingCol; c++){
                if(cells[r][c].occupied) return null; // other frames block placement
            }
        }

        // subdivide all cells on endingRow
        if(remainingHeight!=0){ // if we get lucky and remainingHeight == 0, then we don't need to subdivide cells.
            Cell[][] newCells = new Cell[cells.length+1][cells[0].length];
            for(int r=0; r<=endingRow; r++){
                System.arraycopy(cells[r],0,newCells[r],0,cells[r].length);
            }
            for(int c=0; c<cells[0].length; c++){
                newCells[endingRow][c].height = cells[endingRow][c].height+remainingHeight;
            }
            for(int c=0; c<cells[0].length; c++){
                Cell cellAbove = cells[endingRow][c];
                newCells[endingRow+1][c] = new Cell(cellAbove.x,cellAbove.y+cellAbove.height,cellAbove.width,
                        -remainingHeight);
                newCells[endingRow+1][c].occupied = cellAbove.occupied;
            }
            for(int r=endingRow+1; r<cells.length; r++){
                System.arraycopy(cells[r],0,newCells[r+1],0,cells[r].length);
            }
            cells = newCells;
        }

        // subdivide all cells on endingCol
        if(remainingWidth<0){
            Cell[][] newCells = new Cell[cells.length][cells[0].length+1];
            for(int r=0; r<cells.length; r++){
                System.arraycopy(cells[r],0,newCells[r],0,endingCol+1);
            }
            for(int r=0; r<cells.length; r++){
                newCells[r][endingCol].width = cells[r][endingCol].width+remainingWidth;
            }
            for(int r=0; r<cells.length; r++){
                Cell cellLeft = cells[r][endingCol];
                newCells[r][endingCol+1] = new Cell(cellLeft.x+cellLeft.width,cellLeft.y, -remainingWidth,
                        cellLeft.height);
                newCells[r][endingCol+1].occupied = cellLeft.occupied;
            }
            for(int r=0; r<cells.length; r++){
                System.arraycopy(cells[r],endingCol+1,newCells[r],endingCol+2,
                        cells[r].length-endingCol-1);
            }
            cells = newCells;
        }
        else if(remainingWidth>0){
            Cell[][] newCells = new Cell[cells.length][cells[0].length+1];
            for(int r=0; r<cells.length; r++){
                System.arraycopy(cells[r],0,newCells[r],0,maxCol);
            }
            for(int r=0; r<cells.length; r++){
                Cell cellLeft = newCells[r][maxCol];
                cellLeft.width = remainingWidth;
                newCells[r][maxCol+1] = new Cell(cellLeft.x + cellLeft.width, cellLeft.y, Integer.MAX_VALUE,
                        cellLeft.height);
            }
            cells = newCells;
        }

        // Finally, mark the appropriate cells as occupied
        for(int r=row; r<=endingRow; r++){
            for(int c=col; c<=endingCol; c++){
                cells[r][c].occupied = true;
            }
        }

        // compute and set the frame's position
        frame.x = 0;
        frame.y = 0;
        for(int r=0; r<row; r++) frame.y += cells[r][0].height;
        for(int c=0; c<col; c++) frame.x += cells[0][c].width;

        return cells;
    }

    // The sprite sheet is divided into more and more "cells" as frames are arranged on it. The cells designate wholly
    // occupied or wholly unoccupied regions of the sprite sheet.
    private class Cell extends Rectangle{
        private boolean occupied = false;
        Cell(int x, int y, int width, int height){
            super(x,y,width,height);
        }
    }

    // extracts the index from a png filename.
    private int getIndex(File file){
        String name = file.getName();
        if(!name.startsWith(baseName) || !name.endsWith(".png")) return -1;
        return Integer.parseInt(name.substring(baseName.length(),name.length()-4));
    }

    // for debugging
    private void printSpriteMeta(List<SpriteFrameMeta> spriteFrameMetas){
        for(SpriteFrameMeta frame : spriteFrameMetas){
            System.out.println("frame " + frame.index + " is " + frame.width + "x" + frame.height);
        }
    }

    // After cropping out unnecessary transparent pixels the width, height, index, anchor point location, and File
    // pointer of a png file are stored in a SpriteFrameMeta. These objects represent the individual frames and are used
    // by the algorithm for finding an arrangement of the frames on the sprite sheet while keeping track of which frame
    // is placed where.
    private class SpriteFrameMeta extends Rectangle{
        private int index;
        private File imageFile;
        private Point2D anchorPoint;

        private SpriteFrameMeta(int index, int width, int height, double anchorX, double anchorY, File imageFile){
            this.index = index;
            this.width = width;
            this.height = height;
            this.anchorPoint = new Point2D(anchorX, anchorY);
            this.imageFile = imageFile;
        }
        private int getIndex(){
            return index;
        }
    }
}
