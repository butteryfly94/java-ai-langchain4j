import os
import sys

sys.path.insert(0, r"D:\bak\ai-langchain4j\_docdeps")
import pypdfium2 as pdfium

pdf_path = r"D:\bak\ai-langchain4j-prd\resume_render_after\马亚平_优化版.pdf"
out_dir = r"D:\bak\ai-langchain4j-prd\resume_render_after\png"
os.makedirs(out_dir, exist_ok=True)

pdf = pdfium.PdfDocument(pdf_path)
for index in range(len(pdf)):
    image = pdf[index].render(scale=2).to_pil()
    image.save(os.path.join(out_dir, f"page-{index + 1}.png"))
print(f"rendered {len(pdf)} pages")
