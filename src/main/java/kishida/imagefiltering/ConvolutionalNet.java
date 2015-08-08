package kishida.imagefiltering;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.Random;
import java.util.function.DoubleToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import sun.security.krb5.JavaxSecurityAuthKerberosAccess;

/**
 *
 * @author naoki
 */
public class ConvolutionalNet {
    static final double ep = 0.000000001;

    static class Img{

        public Img(Path filename, boolean inverse) {
            this.filename = filename;
            this.inverse = inverse;
        }
        Path filename;
        boolean inverse;
    }
    
    static abstract class ImageNouralLayer{
        String name;
        double[][][] result;
        ImageNouralLayer preLayer;

        public ImageNouralLayer(String name) {
            this.name = name;
        }
        
        double[][][] forward(){
            return forward(preLayer.result);
        }
        double[][][] backword(double[][][] delta){
            return backword(preLayer.result, delta);
        }
        
        abstract double[][][] forward(double[][][] in);
        abstract double[][][] backword(double[][][] in, double[][][] delta);

        public String getName() {
            return name;
        }

        public double[][][] getResult() {
            return result;
        }
        
    }
    
    static class InputFilter extends ImageNouralLayer{

        public InputFilter() {
            super("入力");
        }

        @Override
        double[][][] forward(double[][][] in) {
            this.result = in;
            return result;
        }

        @Override
        double[][][] backword(double[][][] in, double[][][] delta) {
            // do nothing
            return null;
        }
        
    }
    
    /** 畳み込み層 */
    static class ConvolutionLayer extends ImageNouralLayer{
        double[][][][] filter;
        double[] bias;
        int stride;
        public ConvolutionLayer(String name, int filterCount, int channel, int size, int stride) {
            super(name);
            this.filter = Stream.generate(() -> Stream.generate(() -> createRandomFilter(size))
                            .limit(channel).toArray(len -> new double[len][][]))
                        .limit(filterCount).toArray(len -> new double[len][][][]);
            this.bias = DoubleStream.generate(() -> r.nextDouble()).limit(filterCount).toArray();
            this.stride = stride;
        }
        /** 畳み込みフィルタを適用する */
        @Override
        double[][][] forward(double[][][] img) {
            int width = img[0].length;
            int height = img[0][0].length;
            int filterSize = filter[0][0].length;
            result = new double[filter.length][width / stride][height / stride];
            IntStream.range(0, filter.length).parallel().forEach(fi ->{
                for(int x = 0; x < width / stride; ++x){
                    for(int y = 0; y < height / stride; ++y){
                        for(int ch = 0; ch < filter[fi].length; ++ch){
                            for(int i = 0; i < filter[0][0].length; ++i){
                                int xx = x * stride + i - filterSize / 2;
                                if(xx < 0 || xx >= width){
                                    continue;
                                }
                                for(int j = 0; j < filter[0][0][0].length; ++j){
                                    int yy = y * stride + j - filterSize / 2;
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
                for(int x = 0; x < width / stride; ++x){
                    for(int y = 0; y < height / stride; ++y){
                        if(result[fi][x][y] < 0){
                            result[fi][x][y] = 0;
                        }
                    }
                }
            });
            return result;
        }

        /** 畳み込み層の学習 */
        @Override
        double[][][] backword(double[][][] input, double[][][] delta){
            double[][][] newDelta = new double[input.length][input[0].length][input[0][0].length];
            double[][][][] oldfilter = new double[filter.length][filter[0].length][filter[0][0].length][];
            for(int f = 0; f < filter.length; ++f){
                for(int ch = 0; ch < filter[f].length; ++ch){
                    for(int i = 0; i < filter[f][ch].length; ++i){
                        oldfilter[f][ch][i] = Arrays.copyOf(filter[f][ch][i], filter[f][ch][i].length);
                    }
                }
            }
            
            IntStream.range(0, filter.length).parallel().forEach(f -> {
                for(int ch = 0; ch < filter[0].length; ++ch){
                    for(int x = 0; x < input[0].length / stride; ++x){
                        for(int y = 0; y < input[0][0].length / stride; ++y){
                            for(int i = 0; i < filter[0][0].length; ++i){
                                int xx = x * stride + i - filter[0][0].length / 2;
                                if(xx < 0 || xx >= input[0].length){
                                    continue;
                                }
                                for(int j = 0; j < filter[0][0][0].length; ++j){
                                    int yy = y * stride + j - filter[0][0][0].length / 2;
                                    if(yy < 0 || yy >= input[0][0].length){
                                        continue;
                                    }
                                    double d = (input[ch][xx][yy] > 0 ? 1 : 0) * delta[f][x][y];
                                    newDelta[ch][i][j] += d * oldfilter[f][ch][i][j];
                                    filter[f][ch][i][j] += d * ep;
                                }
                            }
                            bias[f] += ep * delta[f][x][y];
                        }
                    }
                }
            });
            return newDelta;
        }
    }
    
    static class Normalize extends ImageNouralLayer{
        double range;
        double average;

        public Normalize(String name) {
            super(name);
        }
        
        @Override
        double[][][] forward(double[][][] data){
            result = new double[data.length][data[0].length][data[0][0].length];
            for(int i = 0; i < data.length; ++i){
                DoubleSummaryStatistics st = Arrays.stream(data[i])
                        .flatMapToDouble(Arrays::stream)
                        .summaryStatistics();
                average = st.getAverage();
                range = st.getMax() - average;
                if(range == 0){
                    // rangeが0になるようであれば、割らないようにする
                    range = 1;
                }
                for(int j = 0; j < data[i].length; ++j){
                    for(int k = 0; k < data[i][j].length; ++k){
                        result[i][j][k] = (data[i][j][k] - average) / range;
                    }
                }

            }
            return result;
        }
        @Override
        double[][][] backword(double[][][] in, double[][][] data){
            double[][][] newDelta = new double[data.length][data[0].length][data[0][0].length];
            for(int i = 0; i < data.length; ++i){
                for(int j = 0; j < data[i].length; ++j){
                    for(int k = 0; k < data[i][j].length; ++k){
                        newDelta[i][j][k] = data[i][j][k] * range + average;
                    }
                }
            }
            return newDelta;
        }
    }
    
    
    static class MaxPoolingLayer extends ImageNouralLayer{
        int size;
        int stride;

        public MaxPoolingLayer(String name, int size, int stride) {
            super(name);
            this.size = size;
            this.stride = stride;
        }
        /** プーリング(max) */
        @Override
        double[][][] forward(double[][][] data){
            result = new double[data.length][data[0].length / stride][data[0][0].length / stride];
            IntStream.range(0, data.length).parallel().forEach(ch -> {
                for(int x = 0; x < data[0].length / stride; ++x){
                    for(int y = 0; y < data[0][0].length / stride; ++y){
                        double max = Double.NEGATIVE_INFINITY;
                        for(int i = 0; i < size; ++i){
                            int xx = x * stride + i - size / 2;
                            if(xx < 0 || xx >= data[0].length){
                                continue;
                            }
                            for(int j = 0; j < size; ++j){
                                int yy = y * stride + j - size / 2;
                                if(yy < 0 || yy >= data[0][0].length){
                                    continue;
                                }
                                if(max < data[ch][xx][yy]){
                                    max = data[ch][xx][yy];
                                }
                            }
                        }
                        result[ch][x][y] = max;
                    }
                }
            });
            return result;
        }

        @Override
        double[][][] backword(double[][][] in, double[][][] delta){
            double[][][] newDelta = new double[in.length][in[0].length][in[0][0].length];
            IntStream.range(0, in.length).parallel().forEach(ch -> {
                for(int x = 0; x < in[0].length / stride; ++x){
                    for(int y = 0; y < in[0][0].length / stride; ++y){
                        double max = Double.NEGATIVE_INFINITY;
                        int maxX = 0;
                        int maxY = 0;
                        for(int i = 0; i < size; ++i){
                            int xx = x * stride + i - size / 2;
                            if(xx < 0 || xx >= in[0].length){
                                continue;
                            }
                            for(int j = 0; j < size; ++j){
                                int yy = y * stride + j - size / 2;
                                if(yy < 0 || yy >= in[0][0].length){
                                    continue;
                                }
                                if(max < in[ch][xx][yy]){
                                    max = in[ch][xx][yy];
                                    maxX = xx;
                                    maxY = yy;
                                }
                            }
                        }
                        newDelta[ch][maxX][maxY] = delta[ch][x][y];
                    }
                }
            });


            return newDelta;
        }
    }
    
    static class FullyConnect{
        double[][] weight;
        double bias;
        int out;
        double[] result;
        public FullyConnect(int in, int out) {
            this.out = out;
            weight = Stream.generate(() -> 
                    DoubleStream.generate(() -> r.nextDouble() * 2 - 1).limit(out).toArray()
            ).limit(in).toArray(len -> new double[len][]);
            bias = r.nextDouble();
        }
        
        public double[] forward(double[] in){
            result = new double[out];
            for(int j = 0; j < out; ++j){
                for(int i = 0; i < in.length; ++i){
                    result[j] += in[i] * weight[i][j];
                }
                result[j] += bias;
            }
            
            return result;
        }
        public double[] backward(double[] in, double[] delta){
            double[][] oldweight = new double[weight.length][];
            for(int i = 0; i < weight.length; ++i){
                oldweight[i] = Arrays.copyOf(weight[i], weight[i].length);
            }
            double[] newDelta = new double[in.length];
            
            for(int j = 0; j < out; ++j){
                for(int i = 0; i < in.length; ++i){
                    double d = (in[i] > 0 ? 1 : 0) * delta[j];
                    newDelta[i] = d * oldweight[i][j];
                    weight[i][j] += d * ep;
                    
                }
                bias += delta[j] * ep;
            }
            return newDelta;
        }
    }
    static double[][][] norm0(double[][][] data){
        return data;
    }
    public static void main(String[] args) throws IOException {
        JFrame f = createFrame();
        f.setVisible(true);
        
        Path dir = Paths.get("C:\\Users\\naoki\\Desktop\\sampleimg");
        List<String> categories = Files.list(dir)
                .filter(p -> Files.isDirectory(p))
                .map(p -> p.getFileName().toString())
                .collect(Collectors.toList());
        List<ImageNouralLayer> layers = new ArrayList<>();
        InputFilter input = new InputFilter();
        layers.add(input);
        //一段目
        layers.add(new ConvolutionLayer("norm1", 48, 3, 11, 4));
        //一段目のプーリング
        layers.add(new MaxPoolingLayer("pool1", 3, 2));
        //一段目の正規化
        layers.add(new Normalize("norm1"));
        //二段目
        layers.add(new ConvolutionLayer("conv2", 96, 48, 5, 2));
        //二段目のプーリング
        layers.add(new MaxPoolingLayer("pool2", 3, 2));
        
        Normalize norm2 = new Normalize("norm2");
        layers.add(norm2);
        
        //全結合1
        FullyConnect fc1 = new FullyConnect(6144, 32);
        //全結合2
        FullyConnect fc2 = new FullyConnect(32, categories.size());
        
        Path p = dir.resolve("cat\\DSC00800.JPG");
        String catName = p.getParent().getFileName().toString();
        double[] correctData = categories.stream()
                .mapToDouble(name -> name.equals(catName) ? 1 : 0)
                .toArray();
        
        BufferedImage readImg = ImageIO.read(p.toFile());
        BufferedImage resized = resize(readImg, 256, 256);
        double[][][] readData = norm0(imageToArray(resized));

        //元画像の表示
        org.setIcon(new ImageIcon(resized));
        
        double[] output = forward(layers, fc1, fc2, readData, correctData);
        //一段目のフィルタの表示
        ConvolutionLayer conv1 = (ConvolutionLayer) layers.get(1);
        for(int i = 0; i < conv1.filter.length; ++i){
            filtersLabel[i].setIcon(new ImageIcon(resize(arrayToImage(conv1.filter[i]), 44, 44, false, false)));
        }
        //フィルタ後の表示
        //全結合一段の表示
        firstFc.setIcon(new ImageIcon(createGraph(256, 128, fc1.result)));
        //全結合二段の表示
        lastResult.setIcon(new ImageIcon(createGraph(256, 128, output)));
    }
    
    static Image createGraph(int width, int height, double[] data){
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = (Graphics2D) result.getGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        DoubleSummaryStatistics summary = Arrays.stream(data).summaryStatistics();
        DoubleToIntFunction f = d -> (int)(height - 
                (d - summary.getMin()) / (summary.getMax() - summary.getMin()) * height);
        g.setColor(Color.BLACK);
        g.drawLine(0, f.applyAsInt(0), width, f.applyAsInt(0));
        for(int i = 0; i < data.length; ++i){
            int left = i * width / data.length;
            int bottom = f.applyAsInt(0);
            int right = (i + 1) * width / data.length;
            int top = f.applyAsInt(data[i]);
            g.fillRect(left, Math.min(top, bottom), right - left - 1, Math.abs(bottom - top));
        }
        
        return result;
    }
    
    static JLabel org = new JLabel();
    static JLabel firstFc = new JLabel();
    static JLabel lastResult = new JLabel();
    static JLabel[] filtersLabel = Stream.generate(() -> new JLabel()).limit(48)
            .toArray(size -> new JLabel[size]);
    
    static JFrame createFrame(){
        JFrame f = new JFrame("畳み込みニューラルネット");
        f.setLayout(new GridLayout(2, 1));
        
        JPanel north = new JPanel();
        // 上段
        f.add(north);
        north.setLayout(new GridLayout(1, 2));
        north.add(org);
        // 上段右
        JPanel northRight = new JPanel();
        north.add(northRight);
        northRight.setLayout(new GridLayout(2, 1));
        northRight.add(firstFc);
        northRight.add(lastResult);
        
        //下段
        JPanel middle = new JPanel();
        f.add(middle);
        middle.setLayout(new GridLayout(6, 8));
        for(int i = 0; i < filtersLabel.length; ++i){
            middle.add(filtersLabel[i]);
        }
        f.setSize(540, 580);
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        return f;
    }
    
    static double[] forward(List<ImageNouralLayer> layers, FullyConnect fc1, FullyConnect fc2,
            double[][][] readData, double[] correctData){
        ImageNouralLayer norm2 = layers.get(layers.size() - 1);
        layers.get(0).result = readData;
        for(int i = 1; i < layers.size(); ++i){
            layers.get(i).preLayer = layers.get(i - 1);
            layers.get(i).forward();
        }
        /*
        //一段目のフィルタをかける
        double[][][] filtered1 = conv1.forward(readData);
        //プーリング
        double[][][] pooled1 = pool1.forward(filtered1);
        double[][][] pooled1norm = norm1.forward(pooled1);
        //二段目のフィルタをかける
        double[][][] filtered2 = conv2.forward(pooled1norm);
        //プーリング
        double[][][] pooled2 = pool2.forward(filtered2);
        double[][][] pooled2norm = norm2.forward(pooled2);
        */
        double[] flattenPooled2 = flatten(norm2.getResult());
        //全結合一段
        double[] fc1out = fc1.forward(flattenPooled2);
        double[] re = Arrays.stream(fc1out).map(d -> d > 0 ? d : 0).toArray();
        //全結合二段
        double[] fc2out = fc2.forward(re);
        System.out.println(Arrays.stream(fc2out).mapToObj(d -> String.format("%.3f", d)).collect(Collectors.joining(",")));
        //ソフトマックス
        double[] output = softMax(fc2out);
        System.out.println(Arrays.stream(output).mapToObj(d -> String.format("%.3f", d)).collect(Collectors.joining(",")));
        //全結合二段の逆伝播
        double[] delta = IntStream.range(0, output.length)
                .mapToDouble(idx -> correctData[idx] - output[idx])
                .toArray();
        double[] deltaFc2 = fc2.backward(re, delta);
        //全結合一段の逆伝播
        double[] deltaFc1 = fc1.backward(flattenPooled2, deltaFc2);
        
        double[][][] deltaFc1Dim3 = divide3dim(deltaFc1, norm2.result[0].length, norm2.result[0][0].length);
        //プーリングの逆伝播
        for(int i = layers.size() - 1; i >= 1; --i){
            deltaFc1Dim3 = layers.get(i).backword(deltaFc1Dim3);
        }
        /*
        double[][][] deltaNorm2 = norm2.backword(null, deltaFc1Dim3);
        printDim("deltaNorm2", deltaNorm2);
        double[][][] deltaPool2 = pool2.backword(filtered2, deltaNorm2);
        //二段目のフィルタの逆伝播
        double[][][] deltaConv2 = conv2.backword(pooled1norm, deltaPool2);
        //プーリングの逆伝播
        double[][][] deltaPool1 = pool1.backword(filtered1, norm1.backword(null, deltaConv2));
        
        //一段目のフィルタの逆伝播
        double[][][] all1 = Arrays.stream(readData).map(ch -> 
                Arrays.stream(ch).map(row -> 
                        Arrays.stream(row).map(d -> 1)
                                .toArray())
                        .toArray(size -> new double[size][]))
                .toArray(size -> new double[size][][]);
        conv1.backword(all1, deltaPool1);
        */
        return output;
    }
    static void printDim(String name, double[][][] data){
        System.out.printf("%s:%dx%dx%d%n", name,
                data[0].length, data[0][0].length, data.length);
    }
    static void printData(double[][] data){
        System.out.println(Arrays.stream(data)
                .map(da -> Arrays.stream(da)
                        .mapToObj(d -> String.format("%.3f", d))
                        .collect(Collectors.joining(",")))
                .collect(Collectors.joining("\n")));
    }
    static double[] softMax(double[] output){
        double total = Arrays.stream(output).parallel()
                .map(d -> Math.exp(d))
                .sum();
        return Arrays.stream(output).parallel()
                .map(d -> Math.exp(d) / total)
                .toArray();
    }
    
    static Random r = new Random();
    static double[][] createRandomFilter(int size){
        double [][] result = new double[size][size];
        for(int i = 0; i < size; ++i){
            for(int j = 0; j < size; ++j){
                result[i][j] = r.nextDouble();
            }
        }

        
        return result;
    }
    
    static double[][][] divide3dim(double[] data, int sec, int third){
        return IntStream.range(0, data.length / sec / third).mapToObj(i -> 
            IntStream.range(0, sec).mapToObj(j -> 
                Arrays.copyOfRange(data, 
                        i * sec * third +j * third, 
                        i * sec * third + j * third + third)
            ).toArray(size -> new double[size][])
        ).toArray(size -> new double[size][][]);
    }
    
    static double[] flatten(double[][][] data){
        return Arrays.stream(data)
                .flatMap(Arrays::stream)
                .flatMapToDouble(Arrays::stream)
                .toArray();
    }
    
    /** 値のクリッピング */
    static int clip(double c){
        if(c < 0) return 0;
        if(c > 255) return 255;
        return (int)c;
    }

    
    private static BufferedImage resize(BufferedImage imgRead, int width, int height) {
        return resize(imgRead, width, height, true, false);
    }
    private static BufferedImage resize(BufferedImage imgRead, int width, int height, boolean bicubic, boolean inverse) {
        /*
        if(imgRead.getWidth() * height > imgRead.getHeight() * width){
            height = imgRead.getHeight() * width / imgRead.getWidth();
        }else{
            width = imgRead.getWidth() * height / imgRead.getHeight();
        }*/
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics g = img.getGraphics();
        if(bicubic){
            ((Graphics2D)g).setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        }
        if(inverse){
            g.drawImage(imgRead, width, 0, -width, height, null);
        }else{
            g.drawImage(imgRead, 0, 0, width, height, null);
            
        }
        g.dispose();
        return img;
    }    
        
    static BufferedImage arrayToImage(double[][][] filteredData) {
        BufferedImage filtered = new BufferedImage(
                filteredData[0].length, filteredData[0][0].length,
                BufferedImage.TYPE_INT_RGB);
        for(int x = 0; x < filteredData[0].length; ++x){
            for(int y = 0; y < filteredData[0][0].length; ++y){
                filtered.setRGB(x, y,
                        ((int)clip(filteredData[0][x][y] * 255) << 16) +
                        ((int)clip(filteredData[1][x][y] * 255) << 8) +
                         (int)clip(filteredData[2][x][y] * 255));
            }
        }
        return filtered;
    }        
    /** 画像から配列へ変換 */
    private static double[][][] imageToArray(BufferedImage img) {
        int width = img.getWidth();
        int height = img.getHeight();
        double[][][] imageData = new double[3][width][height];
        for(int x = 0; x < width; ++x){
            for(int y = 0; y < height; ++y){
                int rgb = img.getRGB(x, y);
                imageData[0][x][y] = (rgb >> 16 & 0xff) / 255.;
                imageData[1][x][y] = (rgb >> 8 & 0xff) / 255.;
                imageData[2][x][y] = (rgb & 0xff) / 255.;
            }
        }
        return imageData;
    }    
    
}