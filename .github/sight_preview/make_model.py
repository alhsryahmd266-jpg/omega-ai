"""Builds a tiny vision model for the emulator run: float32 NHWC [1,320,320,3] in, end-to-end boxes [1,300,6] out
(x1, y1, x2, y2, score, class in the pixels of the 320 picture). It ignores the picture and always answers the same three boxes, so the
run can check that the app opens a real .tflite with the real library, reads its shapes and decodes what it answers.
This is NOT the owner's model and uses none of his files."""
import sys

import numpy as np
import tensorflow as tf

out = sys.argv[1]
rows = np.zeros((1, 300, 6), dtype=np.float32)
rows[0, 0] = [40, 60, 100, 200, 0.92, 0]    # a body
rows[0, 1] = [52, 62, 82, 94, 0.81, 1]      # its head
rows[0, 2] = [210, 200, 240, 260, 0.12, 0]  # too weak to count
const = tf.constant(rows)


class M(tf.Module):
    @tf.function(input_signature=[tf.TensorSpec([1, 320, 320, 3], tf.float32)])
    def __call__(self, x):
        # the output depends on the input only through "* 0.0", which the converter keeps (it cannot fold it for floats)
        return const + tf.reduce_mean(x) * 0.0


m = M()
conv = tf.lite.TFLiteConverter.from_concrete_functions([m.__call__.get_concrete_function()], m)
data = conv.convert()
open(out, "wb").write(data)
print("wrote", out, len(data), "bytes")
