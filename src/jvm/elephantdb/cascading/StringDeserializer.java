package elephantdb.cascading;

import elephantdb.Utils;
import org.apache.hadoop.io.BytesWritable;


public class StringDeserializer implements Deserializer {
    public Object deserialize(BytesWritable bw) {
        return Utils.deserializeString(bw);
    }
}
