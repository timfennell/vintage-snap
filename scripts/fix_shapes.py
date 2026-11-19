"""Writes a static-shape copy of a Depth Anything ONNX model (NNAPI and QNN
need fixed shapes; the onnx-community export has free batch/height/width).

    python fix_shapes.py model.onnx 392 518   ->  model_392x518.onnx
"""
import sys
import onnx

src, h, w = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
m = onnx.load(src)
dims = m.graph.input[0].type.tensor_type.shape.dim
for d, v in zip(dims, (1, 3, h, w)):
    d.ClearField("dim_param")
    d.dim_value = v
for o in m.graph.output:
    for d in o.type.tensor_type.shape.dim:
        if d.dim_param:
            d.ClearField("dim_param")
out = src.replace(".onnx", f"_{h}x{w}.onnx")
onnx.save(m, out)
print(out, [d.dim_value for d in dims])
