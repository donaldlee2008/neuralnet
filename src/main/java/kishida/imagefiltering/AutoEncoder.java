/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package kishida.imagefiltering;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 *
 * @author naoki
 */
public class AutoEncoder {
    static final int stride = 4;
    static final double ep = 0.00000001;

    static class Img{

        public Img(Path filename, boolean inverse) {
            this.filename = filename;
            this.inverse = inverse;
        }
        Path filename;
        boolean inverse;
    }

    public static void main(String[] args) {
        JFrame f = new JFrame("自己符号化器");

        f.setSize(600, 400);
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.setLayout(new GridLayout(3, 1));
        f.setVisible(true);
        JPanel top = new JPanel(new GridLayout(1, 2));
        f.add(top);
        JLabel left = new JLabel();
        top.add(left);
        JLabel right = new JLabel();
        top.add(right);
        JPanel middle = new JPanel(new GridLayout(6, 8));
        f.add(middle);
        JLabel[] labels = IntStream.range(0, 48)
                .mapToObj(i -> new JLabel())
                .peek(middle::add)
                .toArray(i -> new JLabel[i]);
        JPanel bottom = new JPanel(new GridLayout(6, 8));
        f.add(bottom);
        JLabel[] outlabels = IntStream.range(0, 48)
                .mapToObj(i -> new JLabel())
                .peek(bottom::add)
                .toArray(i -> new JLabel[i]);

        Path dir = Paths.get("C:\\Users\\naoki\\Desktop\\sampleimg");
        new Thread(() -> {
            double[][][][] filters = new double[48][][][];
            for(int i = 0; i < filters.length; ++i){
                filters[i] = new double[][][]{
                    createRandomFilter(11),createRandomFilter(11),createRandomFilter(11)
                };
            }
            double[] bias = new Random().doubles(48).toArray();
            double[][][][] outfilters = new double[48][][][];
            for(int i = 0; i < outfilters.length; ++i){
                outfilters[i] = new double[][][]{
                    createRandomFilter(11),createRandomFilter(11),createRandomFilter(11)
                };
            }

            for(int i = 0; i < filters.length; ++i){
                labels[i].setIcon(new ImageIcon(resize(arrayToImage(filters[i]), 44, 44)));
            }
            try {
                List<Img> pathsorg = Files.walk(dir).filter(p -> Files.isRegularFile(p))
                        .flatMap(p -> Stream.of(new Img(p, true), new Img(p, false)))
                        .collect(Collectors.toList());
                List<Img> paths = Collections.nCopies(10, pathsorg).stream().flatMap(l -> l.stream()).collect(Collectors.toList());
                Collections.shuffle(paths, r);
                int[] count = {0};
                paths.forEach((Img im) -> {
                    Path p = im.filename;
                    ++count[0];
                    try {
                        System.out.println(count[0] + ":" + p);
                        BufferedImage readImg = ImageIO.read(p.toFile());
                        if(readImg == null){
                            System.out.println("no image");
                            return;
                        }
                        BufferedImage resized = resize(readImg, 256, 256, im.inverse);
                        left.setIcon(new ImageIcon(resized));
                        double[][][] resizedImage = imageToArray(resized);
                        double[][][] filtered = applyFilter(resizedImage, filters, bias, stride);
                        double[][][] inverseImage = applyInverseFilter(filtered, outfilters, stride);
                        right.setIcon(new ImageIcon(arrayToImage(inverseImage)));
                        double[][][] delta = supervisedLearn(inverseImage, resizedImage, outfilters, filtered, stride);
                        convolutionalLearn(delta, filters, bias, resizedImage, filtered, stride);
                        for(int i = 0; i < filters.length; ++i){
                            labels[i].setIcon(new ImageIcon(resize(arrayToImage(filters[i]), 44, 44)));
                            labels[i].setText(String.format("%.3f", bias[i]));
                        }
                        for(int i = 0; i < outfilters.length; ++i){
                            outlabels[i].setIcon(new ImageIcon(resize(arrayToImage(outfilters[i]), 44, 44)));
                        }
                    } catch (IOException ex) {
                        Logger.getLogger(AutoEncoder.class.getName()).log(Level.SEVERE, null, ex);
                    }

                });
            } catch (IOException ex) {
                Logger.getLogger(AutoEncoder.class.getName()).log(Level.SEVERE, null, ex);
            }
        }).start();
    }

    static double[][][] supervisedLearn(double[][][] data, double[][][] superviser, double[][][][] filters, double[][][] input, int step){
        int width = Math.min(data[0].length, superviser[0].length);
        int height = Math.min(data[0][0].length, superviser[0][0].length);
        double[][][][] oldfilter = new double[filters.length][filters[0].length][filters[0][0].length][];
        for(int f = 0; f < filters.length; ++f){
            for(int ch = 0; ch < filters[f].length; ++ch){
                for(int i = 0; i < filters[f][ch].length; ++i){
                    oldfilter[f][ch][i] = Arrays.copyOf(filters[f][ch][i], filters[f][ch][i].length);
                }
            }
        }
        double localEp = ep / filters[0].length / filters[0].length;
        double[][][]delta = new double[input.length][input[0].length][input[0][0].length];
        IntStream.range(0, filters.length).parallel().forEach(f -> {
            for(int lx = 0; lx < width / step; ++lx){
                int x = lx;
                for(int ly = 0; ly < height / step; ++ly){
                    int y = ly;
                    for(int i = 0; i < filters[0][0].length; ++i){
                        int xx = x * step + i - filters[0][0].length / 2;
                        if(xx < 0 || xx >= width){
                            continue;
                        }
                        for(int j = 0; j < filters[0][0][0].length; ++j){
                            int yy = y * step + j - filters[0][0][0].length / 2;
                            if(yy < 0 || yy >= height){
                                continue;
                            }
                            for(int lch = 0; lch < Math.min(data.length, superviser.length); ++lch){
                                int ch = lch;
                                double c1 = superviser[ch][xx][yy];
                                double c2 = data[ch][xx][yy];
                                //if(c1 < -1) c1 = -1; if(c1 > 1) c1 = 1;
                                //if(c2 < -1) c2 =-1; if(c2 > 1) c2 = 1;
                                double d = (c2 - c1) * (data[ch][xx][yy] > 0 ? 1 : 0) * oldfilter[f][ch][i][j];
                                delta[f][x][y] += d;
                                filters[f][ch][i][j] += d * input[f][x][y] * localEp;
                            }
                        }
                    }
                }
            }
        });
        return delta;
    }

    /** 畳み込み層の学習 */
    static void convolutionalLearn(double[][][] delta, double[][][][] filters, double[] bias, double[][][] input, double[][][] result, int step ){
        double[][][][] oldfilter = new double[filters.length][filters[0].length][filters[0][0].length][];
        for(int f = 0; f < filters.length; ++f){
            for(int ch = 0; ch < filters[f].length; ++ch){
                for(int i = 0; i < filters[f][ch].length; ++i){
                    oldfilter[f][ch][i] = Arrays.copyOf(filters[f][ch][i], filters[f][ch][i].length);
                }
            }
        }
        double localep = ep / filters[0][0][0].length /filters[0][0][0].length;
        IntStream.range(0, filters.length).parallel().forEach(f -> {
            for(int ch = 0; ch < filters[0].length; ++ch){
                for(int x = 0; x < input[0].length / step; ++x){
                    for(int y = 0; y < input[0][0].length / step; ++y){
                        for(int i = 0; i < filters[0][0].length; ++i){
                            int xx = x * step + i - filters[0][0].length / 2;
                            if(xx < 0 || xx >= input[0].length){
                                continue;
                            }
                            for(int j = 0; j < filters[0][0][0].length; ++j){
                                int yy = y * step + j - filters[0][0][0].length / 2;
                                if(yy < 0 || yy >= input[0][0].length){
                                    continue;
                                }
                                double d = (result[f][x][y] > 0 ? 1 : 0) * delta[f][x][y] * oldfilter[f][ch][i][j];
                                filters[f][ch][i][j] += d * input[ch][xx][yy] * localep;
                            }
                        }
                        bias[f] += ep * delta[f][x][y];
                    }
                }
            }
        });
    }

    private static BufferedImage resize(BufferedImage imgRead, int width, int height) {
        return resize(imgRead, width, height, false);
    }
    private static BufferedImage resize(BufferedImage imgRead, int width, int height, boolean inverse) {
        if(imgRead.getWidth() * height > imgRead.getHeight() * width){
            height = imgRead.getHeight() * width / imgRead.getWidth();
        }else{
            width = imgRead.getWidth() * height / imgRead.getHeight();
        }
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics g = img.getGraphics();
        ((Graphics2D)g).setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        if(inverse){
            g.drawImage(imgRead, width, 0, -width, height, null);
        }else{
            g.drawImage(imgRead, 0, 0, width, height, null);

        }
        g.dispose();
        return img;
    }

    /** 画像から配列へ変換 */
    private static double[][][] imageToArray(BufferedImage img) {
        int width = img.getWidth();
        int height = img.getHeight();
        double[][][] imageData = new double[3][width][height];
        for(int x = 0; x < width; ++x){
            for(int y = 0; y < height; ++y){
                int rgb = img.getRGB(x, y);
                imageData[0][x][y] = (rgb >> 16 & 0xff) / 128. - 1;
                imageData[1][x][y] = (rgb >> 8 & 0xff) / 128. - 1;
                imageData[2][x][y] = (rgb & 0xff) / 128. - 1;
            }
        }
        return imageData;
    }

    static Random r = new Random();
    static double[][] createRandomFilter(int size){
        double [][] result = new double[size][size];
        double total = 0;
        for(int i = 0; i < size; ++i){
            for(int j = 0; j < size; ++j){
                result[i][j] = r.nextDouble();
                total += result[i][j];
            }
        }

        return result;
    }

    /** 値のクリッピング */
    static int clip(double c){
        if(c < 0) return 0;
        if(c > 255) return 255;
        return (int)c;
    }

    /** 畳み込みフィルタを適用する */
    static double[][][] applyFilter(double[][][] img, double[][][][] filter, double[] bias, int inStride) {
        int width = img[0].length;
        int height = img[0][0].length;
        int filterSize = filter[0][0].length;
        double[][][] result = new double[filter.length][width / inStride][height / inStride];
        IntStream.range(0, filter.length).parallel().forEach(fi ->{
            for(int x = 0; x < width / inStride; ++x){
                for(int y = 0; y < height / inStride; ++y){
                    for(int ch = 0; ch < filter[fi].length; ++ch){
                        for(int i = 0; i < filter[0][0].length; ++i){
                            int xx = x * inStride + i - filterSize / 2;
                            if(xx < 0 || xx >= width){
                                continue;
                            }
                            for(int j = 0; j < filter[0][0][0].length; ++j){
                                int yy = y * inStride + j - filterSize / 2;
                                if(yy < 0 || yy >= height){
                                    continue;
                                }
                                result[fi][x][y] += img[ch][xx][yy] *
                                        filter[fi][ch][i][j];
                            }
                        }
                    }
                    result[fi][x][y] += bias[fi];
                }
            }
        });
        return result;
    }
    /** フィルタを適用する */
    static double[][][] applyInverseFilter(double[][][] img, double[][][][] filter, int outStride) {
        int width = img[0].length;
        int height = img[0][0].length;
        double[][][] result = new double[filter[0].length][width * outStride][height * outStride];
        int filterSize = filter[0][0].length;
        IntStream.range(0, filter[0].length).parallel().forEach(ch -> {
            for(int lx = 0; lx < width; ++lx){
                int x = lx;
                for(int ly = 0; ly < height; ++ly){
                    int y = ly;
                    for(int li = 0; li < filter[0][0].length; ++li){
                        int i = li;
                        int xx = x * outStride + i - filterSize / 2;
                        if(xx < 0 || xx >= width * outStride){
                            continue;
                        }

                        for(int j = 0; j < filter[0][0][0].length; ++j){
                            int yy = y * outStride + j - filterSize / 2;
                            if(yy < 0 || yy >= height * outStride){
                                continue;
                            }
                            for(int fi = 0; fi < filter.length; ++fi){
                                result[ch][xx][yy] += img[ch][x][y] *
                                        filter[fi][ch][i][j];
                            }
                        }
                    }
                }
            }
        });
        return result;
    }


    static BufferedImage arrayToImage(double[][][] filteredData) {
        BufferedImage filtered = new BufferedImage(
                filteredData[0].length, filteredData[0][0].length,
                BufferedImage.TYPE_INT_RGB);
        for(int x = 0; x < filteredData[0].length; ++x){
            for(int y = 0; y < filteredData[0][0].length; ++y){
                filtered.setRGB(x, y,
                        ((int)clip(filteredData[0][x][y] * 255 + 128) << 16) +
                        ((int)clip(filteredData[1][x][y] * 255 + 128) << 8) +
                         (int)clip(filteredData[2][x][y] * 255 + 128));
            }
        }
        return filtered;
    }
}
